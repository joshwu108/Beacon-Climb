package com.beaconclimb.api.identity;

import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Per-client-address daily cap on account creation (BC-0123c, F5 residual; TM-22, TM-25). Before a
 * registration hashes its password, the accounts the same address created since midnight UTC are
 * counted in SQL ({@code account_creations (client_ip, created_at)}, V8) and, at {@code
 * beacon.auth.register-per-ip-per-day}, the request is 429 RATE_LIMITED with {@code Retry-After}
 * set to the seconds until the next UTC midnight. The address is the same one the public write
 * bucket and the login limiter use ({@code ClientAddress}), so behind an unlisted proxy every
 * browser user shares one cap (docs/runbooks/dev-stack.md). The count and the insert are not one
 * atomic step, so concurrent registrations can overshoot the cap by their own number: it bounds
 * mass account creation, it is not an invariant. This cap is additional to, never instead of, the
 * per-address public write bucket and the Argon2 hash gate.
 */
@Component
public class AccountCreationCap {

  private static final Logger log = LoggerFactory.getLogger(AccountCreationCap.class);

  private final AccountCreationRepository creations;
  private final int registerPerIpPerDay;

  public AccountCreationCap(AccountCreationRepository creations, AuthProperties properties) {
    this.creations = creations;
    this.registerPerIpPerDay = properties.registerPerIpPerDay();
  }

  /**
   * @throws ApiException 429 RATE_LIMITED once {@code clientIp} has created {@code
   *     register-per-ip-per-day} accounts since midnight UTC of {@code now}
   */
  public void require(String clientIp, Instant now) {
    Instant dayStart = now.truncatedTo(ChronoUnit.DAYS);
    long used = creations.countSince(clientIp, dayStart);
    if (used < registerPerIpPerDay) {
      return;
    }
    log.info("registration outcome=DAILY_IP_CAP");
    throw new ApiException(
        ErrorCode.RATE_LIMITED,
        HttpStatus.TOO_MANY_REQUESTS,
        "Too many accounts created from this address today; retry later",
        List.of(),
        Map.of("Retry-After", Long.toString(retryAfterSeconds(now, dayStart))));
  }

  /** Whole seconds, rounded up, until the UTC day after {@code dayStart} begins; at least 1. */
  static long retryAfterSeconds(Instant now, Instant dayStart) {
    Duration untilMidnight = Duration.between(now, dayStart.plus(1, ChronoUnit.DAYS));
    long seconds = (untilMidnight.toNanos() + 999_999_999L) / 1_000_000_000L;
    return Math.max(1, seconds);
  }
}
