package com.beaconclimb.api.materializer;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.ApiApplication;
import com.beaconclimb.api.attempts.AttemptsTestSupport;
import com.beaconclimb.api.features.FeatureAggregates;
import com.beaconclimb.api.features.FeatureAggregates.BandCounts;
import com.beaconclimb.api.features.FeatureSnapshot;
import com.beaconclimb.api.features.FeatureSnapshotRepository;
import com.beaconclimb.api.materializer.MaterializerService.Result;
import com.beaconclimb.api.outbox.KafkaTestContainer;
import com.beaconclimb.api.outbox.StreamTestSupport;
import com.beaconclimb.events.EventSchemas;
import com.beaconclimb.events.EventValidator;
import com.beaconclimb.events.ValidationResult;
import com.beaconclimb.events.ValidationResult.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.util.RawValue;

/**
 * The stream harness plus the materializer: a long retry backoff so a test can stop the container
 * inside the error handler's wait, a {@link ThrowOnceHook} bean that simulates a crash after the
 * commit and before the acknowledgement, and helpers to build climber-features.v1 bytes the way the
 * stream job does ({@code aggregates} embedded as the canonical text), produce them, and read the
 * STREAM row, the committed group offset and the DLQ back.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {
      ApiApplication.class,
      AttemptsTestSupport.TestClockConfig.class,
      MaterializerTestSupport.HookConfig.class
    },
    properties = {
      "beacon.http.write-limit-per-minute=1000",
      "beacon.http.public-burst=1000",
      "beacon.stream.enabled=true",
      "beacon.stream.publisher.poll-interval=200ms",
      "beacon.stream.materializer.retry-backoff=5s"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class MaterializerTestSupport extends StreamTestSupport {

  @TestConfiguration(proxyBeanMethods = false)
  public static class HookConfig {
    @Bean
    ThrowOnceHook throwOnceHook() {
      return new ThrowOnceHook();
    }
  }

  /** Throws once after the next commit when armed; records what it saw. */
  public static final class ThrowOnceHook implements MaterializerHook {
    private final AtomicBoolean armed = new AtomicBoolean();
    private final AtomicReference<CountDownLatch> fired = new AtomicReference<>();
    private final AtomicReference<Result> lastResult = new AtomicReference<>();

    public void arm() {
      fired.set(new CountDownLatch(1));
      armed.set(true);
    }

    public boolean awaitFired(Duration timeout) throws InterruptedException {
      return fired.get().await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public Result lastResult() {
      return lastResult.get();
    }

    @Override
    public void afterCommit(ConsumerRecord<String, byte[]> record, Result result) {
      if (armed.compareAndSet(true, false)) {
        lastResult.set(result);
        fired.get().countDown();
        throw new IllegalStateException("simulated materializer crash after commit, before ack");
      }
    }
  }

  protected static final Duration WAIT = Duration.ofSeconds(30);
  protected static final Instant W = Instant.parse("2026-10-14T14:00:00Z");
  protected static final Instant AS_OF = Instant.parse("2026-10-14T11:00:00.250Z");
  protected static final String CHECKSUM_A =
      "0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a";
  protected static final String CHECKSUM_B =
      "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Autowired protected FeatureSnapshotRepository snapshots;
  @Autowired protected MaterializerService materializer;

  @Autowired
  @Qualifier("materializerContainer")
  protected ConcurrentMessageListenerContainer<String, byte[]> container;

  @Autowired protected KafkaTemplate<String, byte[]> kafka;
  @Autowired protected EventValidator validator;
  @Autowired protected ThrowOnceHook hook;

  /** A representative aggregate set; {@code sends} differ so two checksums map to two payloads. */
  protected static FeatureAggregates aggregates(int sends) {
    TreeMap<String, BandCounts> bands = new TreeMap<>();
    bands.put("INTERMEDIATE", new BandCounts(3, sends));
    bands.put("UNGRADED", new BandCounts(1, 0));
    return new FeatureAggregates(
        2,
        4,
        4,
        sends,
        sends,
        sends,
        bands,
        List.of("demo-route-001:40", "demo-route-002:40"),
        AS_OF,
        20.5,
        2);
  }

  /** climber-features.v1 bytes as the stream job emits them (aggregates embedded as raw text). */
  protected static byte[] featureRecord(
      UUID userId,
      Instant sourceWatermark,
      Instant featureAsOf,
      String checksum,
      String aggregates) {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("schema_version", 1);
    node.put("event_id", UUID.randomUUID().toString());
    node.put("user_id", userId.toString());
    node.put("feature_schema_version", "climber-features.v1");
    node.put("feature_as_of", DateTimeFormatter.ISO_INSTANT.format(featureAsOf));
    node.put("source_watermark", DateTimeFormatter.ISO_INSTANT.format(sourceWatermark));
    node.put("provenance_checksum", checksum);
    node.putRawValue("aggregates", new RawValue(aggregates));
    node.put("produced_at", "2026-10-14T14:00:01Z");
    node.put("producer", "stream");
    return MAPPER.writeValueAsString(node).getBytes(StandardCharsets.UTF_8);
  }

  protected static byte[] featureRecord(
      UUID userId, String checksum, FeatureAggregates aggregates) {
    return featureRecord(userId, W, AS_OF, checksum, aggregates.toJson());
  }

  /** Validates like the listener does and builds the event the service consumes. */
  protected FeatureEvent event(byte[] bytes) {
    ValidationResult verdict = validator.validate(EventSchemas.FEATURES, bytes);
    assertThat(verdict).as(new String(bytes, StandardCharsets.UTF_8)).isInstanceOf(Valid.class);
    return FeatureEvent.from(bytes, ((Valid) verdict).payload());
  }

  protected RecordMetadata produce(UUID userId, byte[] bytes) {
    return produce(userId.toString(), bytes);
  }

  protected RecordMetadata produce(String key, byte[] bytes) {
    try {
      return kafka
          .send(new ProducerRecord<>(KafkaTestContainer.FEATURES_TOPIC, key, bytes))
          .get(WAIT.toSeconds(), TimeUnit.SECONDS)
          .getRecordMetadata();
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("produce failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }

  protected Optional<FeatureSnapshot> streamRow(UUID userId) {
    return snapshots.findStream(userId, FeatureAggregates.SCHEMA_VERSION);
  }

  protected Optional<FeatureSnapshot> syncRow(UUID userId) {
    return snapshots.find(userId, FeatureAggregates.SCHEMA_VERSION);
  }

  protected int streamRows(UUID userId) {
    return jdbc.sql(
            "SELECT count(*) FROM user_feature_snapshots WHERE user_id = :id"
                + " AND source = 'STREAM'")
        .param("id", userId)
        .query(Integer.class)
        .single();
  }

  /** The stored jsonb rendered by Postgres (normalised; compare after canonicalising). */
  protected String storedStreamAggregatesText(UUID userId) {
    return jdbc.sql(
            "SELECT aggregates_json::text FROM user_feature_snapshots WHERE user_id = :id"
                + " AND source = 'STREAM'")
        .param("id", userId)
        .query(String.class)
        .single();
  }

  /** The committed offset of the materializer group on {@code partition}, or -1 when none. */
  protected long committedOffset(int partition) {
    try (Admin admin =
        Admin.create(
            Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KafkaTestContainer.bootstrapServers()))) {
      Map<TopicPartition, OffsetAndMetadata> offsets =
          admin
              .listConsumerGroupOffsets("beacon-materializer")
              .partitionsToOffsetAndMetadata()
              .get(WAIT.toSeconds(), TimeUnit.SECONDS);
      OffsetAndMetadata committed =
          offsets.get(new TopicPartition(KafkaTestContainer.FEATURES_TOPIC, partition));
      return committed == null ? -1L : committed.offset();
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("cannot list group offsets", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }

  protected double counter(String name, String... tags) {
    var counter = meters.find(name).tags(tags).counter();
    return counter == null ? 0.0 : counter.count();
  }

  /**
   * The DLQ records whose original bytes are exactly {@code original}, schema-validated. Other
   * records on the shared topic are ignored without being parsed strictly: DlqRedriveIT puts a
   * deliberately malformed record there.
   */
  protected List<JsonNode> dlqRecordsFor(byte[] original) {
    String base64 = Base64.getEncoder().encodeToString(original);
    return KafkaTestContainer.readToEnd(KafkaTestContainer.DLQ_TOPIC).stream()
        .filter(r -> originalValueBase64(r.value()).equals(base64))
        .map(r -> validDlq(r.value()))
        .toList();
  }

  private static String originalValueBase64(byte[] value) {
    try {
      JsonNode node = MAPPER.readTree(value);
      JsonNode member = node == null ? null : node.get("original_value_base64");
      return member == null || !member.isString() ? "" : member.asString();
    } catch (RuntimeException e) {
      return "";
    }
  }

  protected ConsumerRecord<String, byte[]> dlqRecordOnTopic(String dlqEventId) {
    return KafkaTestContainer.readToEnd(KafkaTestContainer.DLQ_TOPIC).stream()
        .filter(r -> new String(r.value(), StandardCharsets.UTF_8).contains(dlqEventId))
        .findFirst()
        .orElseThrow();
  }

  private JsonNode validDlq(byte[] value) {
    ValidationResult result = validator.validate(EventSchemas.DLQ, value);
    assertThat(result).as(new String(value, StandardCharsets.UTF_8)).isInstanceOf(Valid.class);
    return ((Valid) result).payload();
  }
}
