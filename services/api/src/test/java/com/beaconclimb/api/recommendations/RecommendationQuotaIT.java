package com.beaconclimb.api.recommendations;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.ApiApplication;
import com.beaconclimb.api.attempts.AttemptsTestSupport;
import com.beaconclimb.api.platform.web.ErrorCode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * BC-0123 F5: with the per-user daily cap set to two, a user's third decision of the UTC day is 429
 * RATE_LIMITED with Retry-After (seconds to midnight, at most one hour) and nothing is written,
 * while another user still gets 200.
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

  @Autowired DecisionsProperties properties;

  @Test
  void thirdDecisionOfTheDayIsRateLimitedAndOtherUsersAreNot() {
    assertThat(properties.maxPerUserPerDay()).isEqualTo(2);
    Account capped = registerReady("INTERMEDIATE");
    Account other = registerReady("INTERMEDIATE");

    recommendOk(capped.token(), recommendationBody("TECHNIQUE", 3, UUID.randomUUID(), List.of()));
    recommendOk(capped.token(), recommendationBody("TECHNIQUE", 3, UUID.randomUUID(), List.of()));
    ResponseEntity<String> limited =
        recommend(capped.token(), recommendationBody("TECHNIQUE", 3, UUID.randomUUID(), List.of()));

    assertError(limited, HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED);
    String retryAfter = limited.getHeaders().getFirst("Retry-After");
    assertThat(retryAfter).isNotNull();
    assertThat(Long.parseLong(retryAfter)).isBetween(1L, DecisionQuota.MAX_RETRY_AFTER_SECONDS);
    assertThat(decisionsOf(capped.userId())).isEqualTo(2);

    recommendOk(other.token(), recommendationBody("TECHNIQUE", 3, UUID.randomUUID(), List.of()));
    assertThat(decisionsOf(other.userId())).isEqualTo(1);
  }

  private int decisionsOf(UUID userId) {
    return count("SELECT count(*) FROM recommendation_decisions WHERE user_id = :id", userId);
  }
}
