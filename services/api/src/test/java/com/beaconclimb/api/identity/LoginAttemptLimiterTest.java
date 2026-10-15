package com.beaconclimb.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Sliding window of 10 failures per 15 minutes, per username and per client IP (ADR-0005). */
class LoginAttemptLimiterTest {

  private static final Duration WINDOW = Duration.ofMinutes(15);

  /** Clock whose instant the test moves explicitly. */
  private static final class StepClock extends Clock {
    private Instant now = Instant.parse("2026-10-15T10:00:00Z");

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private final StepClock clock = new StepClock();
  private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(10, WINDOW, clock);

  @Test
  void tenFailuresBlockTheUsernameUntilTheOldestLeavesTheWindow() {
    for (int i = 0; i < 9; i++) {
      limiter.recordFailure("alice", "10.0.0.1");
      clock.advance(Duration.ofSeconds(1));
    }
    assertThat(limiter.retryAfter("alice", "10.0.0.1")).isEmpty();

    limiter.recordFailure("alice", "10.0.0.1");

    assertThat(limiter.retryAfter("alice", "10.0.0.1")).hasValue(WINDOW.minusSeconds(9));
    clock.advance(WINDOW.minusSeconds(9));
    assertThat(limiter.retryAfter("alice", "10.0.0.1")).isEmpty();
  }

  @Test
  void perIpBucketBlocksOtherUsernamesFromTheSameAddress() {
    for (int i = 0; i < 10; i++) {
      limiter.recordFailure("user" + i, "10.0.0.2");
    }

    assertThat(limiter.retryAfter("someone-else", "10.0.0.2")).hasValue(WINDOW);
    assertThat(limiter.retryAfter("someone-else", "10.0.0.3")).isEmpty();
  }

  @Test
  void perUsernameBucketBlocksTheSameUsernameFromAnyAddress() {
    for (int i = 0; i < 10; i++) {
      limiter.recordFailure("bob", "10.0.1." + i);
    }

    assertThat(limiter.retryAfter("bob", "192.168.0.1")).hasValue(WINDOW);
    assertThat(limiter.retryAfter("carol", "192.168.0.1")).isEmpty();
  }

  @Test
  void clearForgetsEverything() {
    for (int i = 0; i < 10; i++) {
      limiter.recordFailure("dave", "10.0.0.9");
    }
    limiter.clear();

    assertThat(limiter.retryAfter("dave", "10.0.0.9")).isEmpty();
  }
}
