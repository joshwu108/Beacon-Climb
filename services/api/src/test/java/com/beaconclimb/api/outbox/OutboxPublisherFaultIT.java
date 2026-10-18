package com.beaconclimb.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.beaconclimb.api.ApiApplication;
import com.beaconclimb.api.attempts.AttemptsTestSupport;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;

/**
 * BC-0301 fault path (PRD 8.3 "Kafka outage preserves the Postgres transaction", ADR-0002): with
 * the broker frozen ({@code docker pause}) one hundred attempts still return 201 and persist,
 * readiness stays 200, the backlog and stale gauges rise and the failure counter ticks; once the
 * broker is back the backlog drains to zero and every event id arrives. {@code stale-after} is two
 * seconds here so the stale gauge can be observed inside the test.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {ApiApplication.class, AttemptsTestSupport.TestClockConfig.class},
    properties = {
      "beacon.http.write-limit-per-minute=1000",
      "beacon.http.public-burst=1000",
      "beacon.stream.enabled=true",
      "beacon.stream.publisher.poll-interval=200ms",
      "beacon.stream.publisher.stale-after=2s"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxPublisherFaultIT extends StreamTestSupport {

  private static final int ATTEMPTS = 100;
  private static final Duration OUTAGE_OBSERVATION = Duration.ofSeconds(90);
  private static final Duration DRAIN = Duration.ofMinutes(3);

  @Test
  void brokerOutageNeverTouchesTheWritePathAndTheBacklogDrainsAfterwards() {
    Account account = registerWithConsent();
    // Whatever earlier classes left unpublished is drained first, so the numbers below are ours.
    await().atMost(DRAIN).until(() -> unpublishedRows() == 0);
    double failuresBefore = failures();

    KafkaTestContainer.pause();
    List<String> attemptIds;
    try {
      attemptIds = createAttempts(account, ATTEMPTS);
      assertThat(attemptIds).hasSize(ATTEMPTS);
      assertThat(
              jdbc.sql("SELECT count(*) FROM attempts WHERE user_id = :id")
                  .param("id", account.userId())
                  .query(Integer.class)
                  .single())
          .isEqualTo(ATTEMPTS);
      assertThat(get("/health/ready", null).getStatusCode()).isEqualTo(HttpStatus.OK);

      await()
          .atMost(OUTAGE_OBSERVATION)
          .pollInterval(Duration.ofMillis(500))
          .untilAsserted(
              () -> {
                assertThat(gauge(OutboxMetrics.BACKLOG_ROWS)).isGreaterThanOrEqualTo(ATTEMPTS);
                assertThat(gauge(OutboxMetrics.BACKLOG_AGE_SECONDS)).isGreaterThan(0);
                assertThat(gauge(OutboxMetrics.STALE_ROWS)).isGreaterThanOrEqualTo(ATTEMPTS);
              });
      assertThat(failures()).isGreaterThan(failuresBefore);
      assertThat(reconciliation.backlog().rows()).isGreaterThanOrEqualTo(ATTEMPTS);
      assertThat(reconciliation.stale(ATTEMPTS + 50)).hasSizeGreaterThanOrEqualTo(ATTEMPTS);
      assertThat(unpublishedRowsOf(account.userId())).isEqualTo(ATTEMPTS);
      assertThat(get("/health/ready", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    } finally {
      KafkaTestContainer.unpause();
    }

    await().atMost(DRAIN).until(() -> unpublishedRowsOf(account.userId()) == 0);
    await().atMost(Duration.ofSeconds(30)).until(() -> gauge(OutboxMetrics.BACKLOG_ROWS) == 0);
    Map<String, String> stored = outboxPayloadsOf(account.userId());
    assertThat(stored).hasSize(ATTEMPTS);
    Map<String, Integer> expected = new HashMap<>();
    stored.keySet().forEach(id -> expected.put(id, 1));
    assertThat(
            KafkaTestContainer.consumeUntil(expected, Duration.ofSeconds(90)).stream()
                .map(KafkaTestContainer::eventId)
                .distinct()
                .filter(stored::containsKey))
        .hasSize(ATTEMPTS);
    assertThat(reconciliation.stale()).isEmpty();
    assertThat(UUID.fromString(attemptIds.get(0))).isNotNull();
  }

  private double failures() {
    return meters.find(OutboxMetrics.PUBLISH_FAILURES).counters().stream()
        .mapToDouble(c -> c.count())
        .sum();
  }
}
