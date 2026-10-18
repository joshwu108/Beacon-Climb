package com.beaconclimb.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * BC-0301 / BC-0109-OUTBOX / BC-0307-PUBLISHER-CRASH: every outbox row reaches {@code attempts.v1}
 * keyed by {@code user_id} with the stored payload bytes and headers, conforming to the contract
 * and free of {@code notes}; a row whose {@code published_at} is cleared is delivered again with
 * the same {@code event_id} (at-least-once, ADR-0002); the meters count what was published and are
 * scraped at {@code /prometheus}.
 */
class OutboxPublisherIT extends StreamTestSupport {

  private static final int ATTEMPTS = 20;
  private static final Duration PUBLISH_WAIT = Duration.ofSeconds(60);
  private static final Duration CONSUME_WAIT = Duration.ofSeconds(60);

  @Test
  void publishesEveryOutboxRowKeyedByUserWithTheStoredBytesAndHeaders() {
    Account account = registerWithConsent();
    List<String> attemptIds = createAttempts(account, ATTEMPTS);
    String first = attemptIds.get(0);
    assertThat(patchAttempt(account.token(), first, "\"1\"", "{\"sent\":false}").getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(patchAttempt(account.token(), first, "\"2\"", "{\"retract\":true}").getStatusCode())
        .isEqualTo(HttpStatus.OK);
    Map<String, String> stored = outboxPayloadsOf(account.userId());
    assertThat(stored).hasSize(ATTEMPTS + 2);

    await().atMost(PUBLISH_WAIT).until(() -> unpublishedRowsOf(account.userId()) == 0);
    Map<String, List<ConsumerRecord<String, byte[]>>> delivered =
        KafkaTestContainer.consumeUntil(expectOnce(stored.keySet()), CONSUME_WAIT).stream()
            .filter(r -> stored.containsKey(KafkaTestContainer.eventId(r)))
            .collect(Collectors.groupingBy(KafkaTestContainer::eventId));

    assertThat(delivered.keySet()).containsExactlyInAnyOrderElementsOf(stored.keySet());
    for (Map.Entry<String, String> row : stored.entrySet()) {
      ConsumerRecord<String, byte[]> record = delivered.get(row.getKey()).get(0);
      String value = new String(record.value(), StandardCharsets.UTF_8);
      assertThat(value)
          .as("value is the stored jsonb text, not re-serialised")
          .isEqualTo(row.getValue());
      assertEventConforms(value);
      JsonNode event = tree(value);
      assertThat(record.key()).isEqualTo(account.userId().toString());
      assertThat(event.get("user_id").asString()).isEqualTo(record.key());
      assertThat(event.get("event_id").asString()).isEqualTo(row.getKey());
      assertThat(KafkaTestContainer.header(record, OutboxPublisher.HEADER_EVENT_TYPE))
          .isEqualTo(event.get("event_type").asString());
      assertThat(KafkaTestContainer.header(record, OutboxPublisher.HEADER_SCHEMA_VERSION))
          .isEqualTo("1");
      assertThat(event.get("attributes").has("grade_numeric")).isTrue();
      assertNoMemberNamed(event, "notes");
    }
    assertThat(
            delivered.values().stream()
                .flatMap(List::stream)
                .map(ConsumerRecord::partition)
                .distinct())
        .as("one key, one partition")
        .hasSize(1);
    assertThat(
            delivered.values().stream()
                .flatMap(List::stream)
                .map(r -> tree(new String(r.value(), StandardCharsets.UTF_8)))
                .filter(e -> e.get("attempt_id").asString().equals(first))
                .map(e -> e.get("event_type").asString()))
        .containsExactlyInAnyOrder("ATTEMPT_RECORDED", "ATTEMPT_CORRECTED", "ATTEMPT_RETRACTED");
    assertGradeMatchesTheRoute(account, attemptIds.get(1), stored);
  }

  @Test
  void clearingPublishedAtDeliversTheSameEventIdASecondTime() {
    Account account = registerWithConsent();
    UUID attemptId = UUID.fromString(createAttempt(account, visibleRouteId()));
    await().atMost(PUBLISH_WAIT).until(() -> unpublishedRowsOf(account.userId()) == 0);
    Map<String, Object> row = outboxRows(attemptId).get(0);
    String eventId = row.get("event_id").toString();
    Object firstPublishedAt = row.get("published_at");

    int reset =
        jdbc.sql("UPDATE attempt_event_outbox SET published_at = NULL WHERE event_id = :id")
            .param("id", UUID.fromString(eventId))
            .update();
    assertThat(reset).isEqualTo(1);

    await()
        .atMost(PUBLISH_WAIT)
        .until(
            () -> {
              Object again = outboxRows(attemptId).get(0).get("published_at");
              return again != null && !again.equals(firstPublishedAt);
            });
    List<ConsumerRecord<String, byte[]>> deliveries =
        KafkaTestContainer.consumeUntil(Map.of(eventId, 2), CONSUME_WAIT).stream()
            .filter(r -> eventId.equals(KafkaTestContainer.eventId(r)))
            .toList();
    assertThat(deliveries)
        .as("at-least-once: a republished row is delivered again")
        .hasSizeGreaterThanOrEqualTo(2);
    assertThat(
            deliveries.stream().map(r -> new String(r.value(), StandardCharsets.UTF_8)).distinct())
        .hasSize(1);
  }

  @Test
  void metersCountPublishedRowsAndAreScrapedAtPrometheus() {
    Account account = registerWithConsent();
    createAttempts(account, 3);
    await().atMost(PUBLISH_WAIT).until(() -> unpublishedRowsOf(account.userId()) == 0);

    assertThat(meters.get(OutboxMetrics.PUBLISHED).counter().count()).isGreaterThanOrEqualTo(3);
    assertThat(meters.find(OutboxMetrics.BACKLOG_ROWS).gauge()).isNotNull();
    assertThat(meters.find(OutboxMetrics.BACKLOG_AGE_SECONDS).gauge()).isNotNull();
    assertThat(meters.find(OutboxMetrics.STALE_ROWS).gauge()).isNotNull();
    assertThat(
            meters
                .find(OutboxMetrics.PUBLISH_FAILURES)
                .tag(OutboxMetrics.REASON_TAG, "kafka")
                .counter())
        .isNotNull();
    await().atMost(Duration.ofSeconds(10)).until(() -> gauge(OutboxMetrics.BACKLOG_ROWS) == 0);
    assertThat(gauge(OutboxMetrics.BACKLOG_AGE_SECONDS)).isEqualTo(0);

    ResponseEntity<String> scrape = get("/prometheus", null);
    assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(scrape.getBody())
        .contains("beacon_outbox_published_total")
        .contains("beacon_outbox_backlog_rows")
        .contains("beacon_outbox_backlog_age_seconds")
        .contains("beacon_outbox_stale_rows")
        .contains("beacon_outbox_publish_failures_total{reason=\"kafka\"");
  }

  private void assertGradeMatchesTheRoute(
      Account account, String attemptId, Map<String, String> stored) {
    JsonNode read = tree(get(ATTEMPTS_PATH + "/" + attemptId, account.token()).getBody());
    JsonNode routeGrade = read.get("route").get("grade_numeric");
    JsonNode eventGrade =
        stored.values().stream()
            .map(this::tree)
            .filter(e -> e.get("attempt_id").asString().equals(attemptId))
            .findFirst()
            .orElseThrow()
            .get("attributes")
            .get("grade_numeric");
    if (routeGrade == null || routeGrade.isNull()) {
      assertThat(eventGrade.isNull()).isTrue();
    } else {
      assertThat(eventGrade.asDouble()).isEqualTo(routeGrade.asDouble());
    }
  }

  private static Map<String, Integer> expectOnce(Iterable<String> eventIds) {
    Map<String, Integer> expected = new java.util.HashMap<>();
    eventIds.forEach(id -> expected.put(id, 1));
    return expected;
  }

  private static final String ATTEMPTS_PATH = "/v1/me/attempts";
}
