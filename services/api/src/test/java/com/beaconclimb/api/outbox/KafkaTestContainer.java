package com.beaconclimb.api.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Shared single-node KRaft broker for the stream tests (BC-0301), the Kafka twin of {@link
 * com.beaconclimb.api.AbstractPostgresIT}: the compose image {@code apache/kafka:4.3.1}, topic
 * auto-creation off and {@code attempts.v1} created with the compose partition count, started once
 * per JVM and stopped by Ryuk. {@link #pause()} / {@link #unpause()} freeze the broker process
 * through the Docker API (the port mapping survives, unlike a stop/start), which is how the fault
 * test simulates an outage.
 */
public final class KafkaTestContainer {

  public static final String IMAGE = "apache/kafka:4.3.1";
  public static final String ATTEMPTS_TOPIC = "attempts.v1";
  public static final int PARTITIONS = 6;
  public static final KafkaContainer KAFKA;

  private static final Duration POLL = Duration.ofMillis(500);

  static {
    KAFKA =
        new KafkaContainer(IMAGE)
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
            .withEnv("KAFKA_HEAP_OPTS", "-Xmx512m -Xms256m");
    KAFKA.start();
    createAttemptsTopic();
  }

  private KafkaTestContainer() {}

  public static String bootstrapServers() {
    return KAFKA.getBootstrapServers();
  }

  public static void pause() {
    KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
  }

  public static void unpause() {
    KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
  }

  /** The {@code event_id} header of a record. */
  public static String eventId(ConsumerRecord<String, byte[]> record) {
    return header(record, OutboxPublisher.HEADER_EVENT_ID);
  }

  public static String header(ConsumerRecord<String, byte[]> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }

  /**
   * Reads {@code attempts.v1} from the earliest offset with a fresh group until every expected
   * event id has been seen at least {@code minimumPerId} times or {@code timeout} elapses, and
   * returns everything read (duplicates included, so at-least-once can be observed).
   */
  public static List<ConsumerRecord<String, byte[]>> consumeUntil(
      Map<String, Integer> expected, Duration timeout) {
    Properties config = new Properties();
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
    config.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-it-" + UUID.randomUUID());
    config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    config.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
    List<ConsumerRecord<String, byte[]>> seen = new ArrayList<>();
    Map<String, Integer> counts = new HashMap<>();
    long deadline = System.nanoTime() + timeout.toNanos();
    try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config)) {
      consumer.subscribe(List.of(ATTEMPTS_TOPIC));
      while (System.nanoTime() < deadline) {
        ConsumerRecords<String, byte[]> records = consumer.poll(POLL);
        for (ConsumerRecord<String, byte[]> record : records) {
          seen.add(record);
          counts.merge(eventId(record), 1, Integer::sum);
        }
        if (expected.entrySet().stream()
            .allMatch(e -> counts.getOrDefault(e.getKey(), 0) >= e.getValue())) {
          break;
        }
      }
    }
    return seen;
  }

  private static void createAttemptsTopic() {
    try (Admin admin =
        Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers()))) {
      admin
          .createTopics(List.of(new NewTopic(ATTEMPTS_TOPIC, PARTITIONS, (short) 1)))
          .all()
          .get(60, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      if (!(e.getCause() instanceof TopicExistsException)) {
        throw new IllegalStateException("cannot create " + ATTEMPTS_TOPIC, e.getCause());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted creating " + ATTEMPTS_TOPIC, e);
    } catch (TimeoutException e) {
      throw new IllegalStateException("timed out creating " + ATTEMPTS_TOPIC, e);
    }
  }
}
