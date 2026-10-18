package com.beaconclimb.api.recommendations;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.ApiApplication;
import com.beaconclimb.api.attempts.AttemptsTestSupport;
import com.beaconclimb.api.platform.web.ErrorCode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * BC-0123 F5 on the pinned application clock (BC-0124c): with the per-user daily cap set to two, a
 * user's third decision of the UTC day is 429 RATE_LIMITED with Retry-After equal to the seconds
 * left until midnight UTC and nothing is written, another user still gets 200, and once the clock
 * has crossed midnight the same user's quota starts afresh (the next cap then answers with the
 * one-hour Retry-After ceiling). The quota and the ledger's {@code created_at} both read the
 * injected {@link java.time.Clock}, so the suite can never straddle a real midnight.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {ApiApplication.class, AttemptsTestSupport.TestClockConfig.class},
    properties = {
      "beacon.http.write-limit-per-minute=1000",
      "beacon.http.public-burst=1000",
      "beacon.decisions.max-per-user-per-day=2"
    })
class RecommendationQuotaIT extends RecommendationsTestSupport {

  private static final Instant TEN_SECONDS_TO_MIDNIGHT = Instant.parse("2026-10-14T23:59:50Z");
  private static final Instant JUST_PAST_MIDNIGHT = Instant.parse("2026-10-15T00:00:01Z");

  @Autowired DecisionsProperties properties;

  @Test
  void thirdDecisionOfTheDayIsRateLimitedUntilUtcMidnightAndOtherUsersAreNot() {
    assertThat(properties.maxPerUserPerDay()).isEqualTo(2);
    testClock.fix(TEN_SECONDS_TO_MIDNIGHT);
    Account capped = registerReady("INTERMEDIATE");
    Account other = registerReady("INTERMEDIATE");

    recommendOk(capped.token(), body());
    recommendOk(capped.token(), body());
    ResponseEntity<String> limited = recommend(capped.token(), body());

    assertError(limited, HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED);
    assertThat(limited.getHeaders().getFirst("Retry-After")).isEqualTo("10");
    assertThat(decisionsOf(capped.userId())).isEqualTo(2);
    assertThat(createdAtOf(capped.userId())).containsOnly(TEN_SECONDS_TO_MIDNIGHT);
    recommendOk(other.token(), body());
    assertThat(decisionsOf(other.userId())).isEqualTo(1);

    testClock.fix(JUST_PAST_MIDNIGHT);

    recommendOk(capped.token(), body());
    recommendOk(capped.token(), body());
    ResponseEntity<String> cappedAgain = recommend(capped.token(), body());

    assertThat(decisionsOf(capped.userId())).isEqualTo(4);
    assertThat(createdAtOf(capped.userId()))
        .containsExactlyInAnyOrder(
            TEN_SECONDS_TO_MIDNIGHT,
            TEN_SECONDS_TO_MIDNIGHT,
            JUST_PAST_MIDNIGHT,
            JUST_PAST_MIDNIGHT);
    assertError(cappedAgain, HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED);
    assertThat(cappedAgain.getHeaders().getFirst("Retry-After"))
        .isEqualTo(Long.toString(DecisionQuota.MAX_RETRY_AFTER_SECONDS));
    assertThat(decisionsOf(capped.userId())).isEqualTo(4);
  }

  private java.util.Map<String, Object> body() {
    return recommendationBody("TECHNIQUE", 3, UUID.randomUUID(), List.of());
  }

  private int decisionsOf(UUID userId) {
    return count("SELECT count(*) FROM recommendation_decisions WHERE user_id = :id", userId);
  }

  private List<Instant> createdAtOf(UUID userId) {
    return jdbc
        .sql("SELECT created_at FROM recommendation_decisions WHERE user_id = :id")
        .param("id", userId)
        .query(OffsetDateTime.class)
        .list()
        .stream()
        .map(OffsetDateTime::toInstant)
        .toList();
  }
}
