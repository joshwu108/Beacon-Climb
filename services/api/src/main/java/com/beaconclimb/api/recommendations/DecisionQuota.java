package com.beaconclimb.api.recommendations;

import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Per-user daily cap on new decisions (BC-0123 F5, TM-25): before a recommendation is computed, the
 * user's decisions since midnight UTC are counted in SQL ({@code recommendation_decisions (user_id,
 * created_at)} index) and, at {@code beacon.decisions.max-per-user-per-day}, the request is 429
 * RATE_LIMITED with {@code Retry-After} set to the seconds until the next UTC midnight, capped at
 * one hour. The count and the insert are not one atomic step, so concurrent requests can overshoot
 * the cap by their own number; it bounds abuse, it is not an invariant.
 */
@Component
public class DecisionQuota {

  static final long MAX_RETRY_AFTER_SECONDS = 3600;
  private static final Logger log = LoggerFactory.getLogger(DecisionQuota.class);

  private final DecisionLedgerRepository ledger;
  private final int maxPerUserPerDay;

  public DecisionQuota(DecisionLedgerRepository ledger, DecisionsProperties properties) {
    this.ledger = ledger;
    this.maxPerUserPerDay = properties.maxPerUserPerDay();
  }

  public void require(UUID userId, Instant now) {
    Instant dayStart = now.truncatedTo(ChronoUnit.DAYS);
    long used = ledger.countSince(userId, dayStart);
    if (used < maxPerUserPerDay) {
      return;
    }
    log.info("recommendation outcome=DAILY_QUOTA user_id={}", userId);
    throw new ApiException(
        ErrorCode.RATE_LIMITED,
        HttpStatus.TOO_MANY_REQUESTS,
        "Daily recommendation limit reached; retry later",
        List.of(),
        Map.of("Retry-After", Long.toString(retryAfterSeconds(now, dayStart))));
  }

  static long retryAfterSeconds(Instant now, Instant dayStart) {
    Duration untilMidnight = Duration.between(now, dayStart.plus(1, ChronoUnit.DAYS));
    long seconds = (untilMidnight.toNanos() + 999_999_999L) / 1_000_000_000L;
    return Math.max(1, Math.min(MAX_RETRY_AFTER_SECONDS, seconds));
  }
}
