package com.beaconclimb.api.dlq;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.dlq.DlqRedriveService.Audit;
import com.beaconclimb.api.dlq.DlqRedriveService.Entry;
import com.beaconclimb.api.dlq.DlqRedriveService.Options;
import com.beaconclimb.api.dlq.DlqRedriveService.Report;
import com.beaconclimb.api.dlq.DlqRedriveService.Result;
import com.beaconclimb.api.outbox.KafkaTestContainer;
import com.beaconclimb.events.DlqHeaders;
import com.beaconclimb.events.DlqRecord;
import com.beaconclimb.events.ErrorCode;
import com.beaconclimb.events.EventValidator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * BC-0302 (b): the redrive against the Testcontainers broker, no Spring context and no Postgres.
 * Three DLQ records are seeded (two selected, one not); the selected originals must reappear on
 * their original topic byte for byte with the attempt incremented and {@code dlq.redriven_from}
 * set, the third untouched, and a second run republishes again (at-least-once, documented).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DlqRedriveIT {

  private static final String DLQ = KafkaTestContainer.DLQ_TOPIC;
  private static final String ATTEMPTS = KafkaTestContainer.ATTEMPTS_TOPIC;
  private static final String USER_A = "018f0000-0000-7000-8000-0000000000a1";
  private static final String USER_B = "018f0000-0000-7000-8000-0000000000b1";
  private static final String USER_C = "018f0000-0000-7000-8000-0000000000c1";

  private final EventValidator validator = new EventValidator();
  private final DlqRedriveService service =
      new DlqRedriveService(
          KafkaTestContainer.bootstrapServers(), validator, Duration.ofSeconds(15));

  private DlqRecord a;
  private DlqRecord b;
  private DlqRecord c;
  private final byte[] originalA = bytes("{\"junk\":\"A-" + UUID.randomUUID() + "\"}");
  private final byte[] originalB = bytes("{\"junk\":\"B-" + UUID.randomUUID() + "\",\"notes\":1}");
  private final byte[] originalC = bytes("{\"junk\":\"C-" + UUID.randomUUID() + "\"}");

  @BeforeAll
  void seed() {
    a = dlq(USER_A, originalA, ErrorCode.NOT_JSON, 1, Map.of("event_id", "ea"));
    b = dlq(USER_B, originalB, ErrorCode.NOTES_PRESENT, 3, Map.of("event_id", "eb"));
    c = dlq(USER_C, originalC, ErrorCode.NOT_JSON, 1, Map.of());
    seedDlq(a);
    seedDlq(b);
    seedDlq(c);
  }

  @Test
  @DisplayName(
      "selected ids are republished byte-identical with attempt+1 and redriven_from; the third is untouched; a second run republishes again")
  void redrivesSelectedRecordsAtLeastOnce() {
    List<String> lines = new ArrayList<>();
    Options selected =
        new Options(DLQ, Set.of(a.eventId().toString(), b.eventId().toString()), false);

    Report first = service.redrive(selected, lines::add);

    assertThat(first.allSucceeded()).isTrue();
    assertThat(first.audits())
        .extracting(Audit::result)
        .containsExactly(Result.PUBLISHED, Result.PUBLISHED);
    assertThat(lines)
        .containsExactlyInAnyOrder(
            "redrive event_id="
                + a.eventId()
                + " original_topic=attempts.v1 original_key="
                + USER_A
                + " attempt=2 result=PUBLISHED",
            "redrive event_id="
                + b.eventId()
                + " original_topic=attempts.v1 original_key="
                + USER_B
                + " attempt=4 result=PUBLISHED");
    Map<String, List<ConsumerRecord<String, byte[]>>> redriven = redrivenByDlqId();
    assertThat(redriven).containsOnlyKeys(a.eventId().toString(), b.eventId().toString());
    ConsumerRecord<String, byte[]> onA = redriven.get(a.eventId().toString()).get(0);
    ConsumerRecord<String, byte[]> onB = redriven.get(b.eventId().toString()).get(0);
    assertThat(onA.value()).isEqualTo(originalA);
    assertThat(onA.key()).isEqualTo(USER_A);
    assertThat(KafkaTestContainer.header(onA, DlqHeaders.ATTEMPT)).isEqualTo("2");
    assertThat(KafkaTestContainer.header(onA, DlqHeaders.ORIGINAL_TOPIC)).isEqualTo("attempts.v1");
    assertThat(KafkaTestContainer.header(onA, DlqHeaders.ERROR_CODE)).isEqualTo("NOT_JSON");
    assertThat(KafkaTestContainer.header(onA, DlqHeaders.CONSUMER)).isEqualTo("stream");
    assertThat(KafkaTestContainer.header(onA, "event_id")).isEqualTo("ea");
    assertThat(onB.value()).isEqualTo(originalB);
    assertThat(onB.key()).isEqualTo(USER_B);
    assertThat(KafkaTestContainer.header(onB, DlqHeaders.ATTEMPT)).isEqualTo("4");
    assertThat(KafkaTestContainer.header(onB, DlqHeaders.REDRIVEN_FROM))
        .isEqualTo(b.eventId().toString());
    assertThat(KafkaTestContainer.readToEnd(ATTEMPTS))
        .noneMatch(r -> java.util.Arrays.equals(r.value(), originalC));

    // At-least-once: the DLQ record is still there, so the same selection publishes again.
    Report second = service.redrive(selected, l -> {});
    assertThat(second.allSucceeded()).isTrue();
    Map<String, List<ConsumerRecord<String, byte[]>>> after = redrivenByDlqId();
    assertThat(after.get(a.eventId().toString())).hasSize(2);
    assertThat(after.get(b.eventId().toString())).hasSize(2);
  }

  @Test
  @DisplayName(
      "dry run prints DRY_RUN lines and publishes nothing; a missing id is SKIPPED NOT_FOUND")
  void dryRunAndNotFound() {
    String missing = "018f0000-0000-7000-8000-00000000ffff";
    long before = KafkaTestContainer.readToEnd(ATTEMPTS).size();
    List<String> lines = new ArrayList<>();

    Report report =
        service.redrive(
            new Options(DLQ, Set.of(c.eventId().toString(), missing), true), lines::add);

    assertThat(KafkaTestContainer.readToEnd(ATTEMPTS)).hasSize((int) before);
    assertThat(report.allSucceeded()).isFalse();
    assertThat(lines)
        .contains(
            "redrive event_id="
                + c.eventId()
                + " original_topic=attempts.v1 original_key="
                + USER_C
                + " attempt=2 result=DRY_RUN",
            "redrive event_id="
                + missing
                + " original_topic=- original_key=- attempt=- result=SKIPPED reason=NOT_FOUND");
  }

  @Test
  @DisplayName(
      "ALL selects every record; a non-conforming DLQ value is SKIPPED with its error code")
  void allAndInvalidDlqRecord() {
    byte[] badDlq =
        bytes(
            "{\"schema_version\":1,\"event_id\":\"018f0000-0000-7000-8000-00000000bad0\","
                + "\"original_topic\":\"attempts.v1\",\"original_key\":\"k\",\"original_value_base64\":\"e30=\","
                + "\"error_code\":\"NOT_JSON\",\"error_message\":\"m\",\"attempt\":0,\"failed_at\":\"2026-10-18T10:00:00Z\"}");
    send(DLQ, "k", badDlq, Map.of());
    List<String> lines = new ArrayList<>();

    Report report = service.redrive(new Options(DLQ, Set.of(), true), lines::add);

    assertThat(report.scanned()).isGreaterThanOrEqualTo(4);
    assertThat(lines)
        .contains(
            "redrive event_id=018f0000-0000-7000-8000-00000000bad0 original_topic=- original_key=- attempt=- result=SKIPPED reason=SCHEMA_VIOLATION")
        .anyMatch(
            l -> l.startsWith("redrive event_id=" + a.eventId()) && l.endsWith("result=DRY_RUN"))
        .anyMatch(
            l -> l.startsWith("redrive event_id=" + c.eventId()) && l.endsWith("result=DRY_RUN"));
    List<Entry> listed = service.list(DLQ);
    assertThat(listed)
        .extracting(Entry::eventId)
        .contains(a.eventId().toString(), b.eventId().toString(), c.eventId().toString());
    assertThat(listed.stream().filter(e -> e.eventId().equals(b.eventId().toString())).findFirst())
        .get()
        .satisfies(
            e -> {
              assertThat(e.errorCode()).isEqualTo("NOTES_PRESENT");
              assertThat(e.attempt()).isEqualTo("3");
              assertThat(e.conforming()).isTrue();
            });
    assertThat(listed).anyMatch(e -> e.eventId().endsWith("bad0") && !e.conforming());
  }

  @Test
  @DisplayName(
      "the redrive profile boots without a datasource and exits 0 on a dry run (no Postgres)")
  void profileBootsWithoutPostgres() {
    int exit =
        DlqRedriveApplication.run(
            new String[] {
              "--spring.profiles.active=redrive",
              "--beacon.stream.bootstrap-servers=" + KafkaTestContainer.bootstrapServers(),
              "--redrive.events=" + a.eventId(),
              "--redrive.dry-run=true"
            });
    int usage =
        DlqRedriveApplication.run(
            new String[] {
              "--spring.profiles.active=redrive",
              "--beacon.stream.bootstrap-servers=" + KafkaTestContainer.bootstrapServers(),
              "--beacon.stream.topics.dlq=somewhere-else.v1",
              "--redrive.events=ALL",
              "--redrive.dry-run=true"
            });
    int listed =
        DlqRedriveApplication.run(
            new String[] {
              "--spring.profiles.active=redrive",
              "--beacon.stream.bootstrap-servers=" + KafkaTestContainer.bootstrapServers(),
              "--redrive.list=true"
            });

    assertThat(DlqRedriveApplication.requested(new String[] {"--spring.profiles.active=redrive"}))
        .isTrue();
    assertThat(DlqRedriveApplication.requested(new String[] {"--spring.profiles.active=import"}))
        .isFalse();
    assertThat(exit).isZero();
    assertThat(usage).isEqualTo(DlqRedriveRunner.EXIT_USAGE);
    assertThat(listed).isZero();
  }

  private Map<String, List<ConsumerRecord<String, byte[]>>> redrivenByDlqId() {
    Map<String, List<ConsumerRecord<String, byte[]>>> byId = new HashMap<>();
    for (ConsumerRecord<String, byte[]> record : KafkaTestContainer.readToEnd(ATTEMPTS)) {
      String from = KafkaTestContainer.header(record, DlqHeaders.REDRIVEN_FROM);
      if (from != null
          && Set.of(a.eventId().toString(), b.eventId().toString(), c.eventId().toString())
              .contains(from)) {
        byId.computeIfAbsent(from, k -> new ArrayList<>()).add(record);
      }
    }
    return byId;
  }

  private static DlqRecord dlq(
      String key, byte[] original, ErrorCode code, int attempt, Map<String, String> headers) {
    return DlqRecord.builder()
        .originalTopic(ATTEMPTS)
        .originalKey(key)
        .originalValue(original)
        .originalPartition(1)
        .originalOffset(5L)
        .originalHeaders(headers)
        .attempt(attempt)
        .error(code, "seeded")
        .consumer("stream")
        .build();
  }

  private static void seedDlq(DlqRecord record) {
    send(DLQ, record.originalKey(), record.json(), record.headers());
  }

  private static void send(String topic, String key, byte[] value, Map<String, String> headers) {
    Map<String, Object> config = new HashMap<>();
    config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainer.bootstrapServers());
    config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    config.put(ProducerConfig.ACKS_CONFIG, "all");
    try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(config)) {
      ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, value);
      headers.forEach((k, v) -> record.headers().add(k, v.getBytes(StandardCharsets.UTF_8)));
      producer.send(record).get(30, TimeUnit.SECONDS);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("seed failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("seed interrupted", e);
    }
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
