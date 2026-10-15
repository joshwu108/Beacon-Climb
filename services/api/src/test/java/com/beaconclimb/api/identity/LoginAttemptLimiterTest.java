package com.beaconclimb.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Sliding window of 10 failures per 15 minutes, per username and per client IP (ADR-0005), with the
 * slot reserved before hashing and released on success (BC-0123 F11), and idle keys swept once the
 * map is large.
 */
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
  void tenReservationsBlockTheUsernameUntilTheOldestLeavesTheWindow() {
    for (int i = 0; i < 9; i++) {
      assertThat(limiter.tryReserve("alice", "10.0.0.1")).isEmpty();
      clock.advance(Duration.ofSeconds(1));
    }
    assertThat(limiter.retryAfter("alice", "10.0.0.1")).isEmpty();

    assertThat(limiter.tryReserve("alice", "10.0.0.1")).isEmpty();

    assertThat(limiter.tryReserve("alice", "10.0.0.1")).hasValue(WINDOW.minusSeconds(9));
    assertThat(limiter.retryAfter("alice", "10.0.0.1")).hasValue(WINDOW.minusSeconds(9));
    clock.advance(WINDOW.minusSeconds(9));
    assertThat(limiter.tryReserve("alice", "10.0.0.1")).isEmpty();
  }

  @Test
  void releaseGivesTheSlotBackInBothBuckets() {
    for (int i = 0; i < 10; i++) {
      assertThat(limiter.tryReserve("alice", "10.0.0.1")).isEmpty();
    }
    assertThat(limiter.tryReserve("alice", "10.0.0.1")).isPresent();

    limiter.release("alice", "10.0.0.1");

    assertThat(limiter.retryAfter("alice", "10.0.0.9")).isEmpty();
    assertThat(limiter.retryAfter("bob", "10.0.0.1")).isEmpty();
    assertThat(limiter.tryReserve("alice", "10.0.0.1")).isEmpty();
    assertThat(limiter.tryReserve("alice", "10.0.0.1")).isPresent();
  }

  @Test
  void perIpBucketBlocksOtherUsernamesFromTheSameAddress() {
    for (int i = 0; i < 10; i++) {
      assertThat(limiter.tryReserve("user" + i, "10.0.0.2")).isEmpty();
    }

    assertThat(limiter.tryReserve("someone-else", "10.0.0.2")).hasValue(WINDOW);
    assertThat(limiter.tryReserve("someone-else", "10.0.0.3")).isEmpty();
  }

  @Test
  void perUsernameBucketBlocksTheSameUsernameFromAnyAddress() {
    for (int i = 0; i < 10; i++) {
      assertThat(limiter.tryReserve("bob", "10.0.1." + i)).isEmpty();
    }

    assertThat(limiter.tryReserve("bob", "192.168.0.1")).hasValue(WINDOW);
    assertThat(limiter.tryReserve("carol", "192.168.0.1")).isEmpty();
  }

  @Test
  void aRefusedReservationReservesNothingInTheOtherBucket() {
    for (int i = 0; i < 10; i++) {
      limiter.tryReserve("user" + i, "10.0.0.2");
    }
    assertThat(limiter.tryReserve("alice", "10.0.0.2")).isPresent();

    for (int i = 0; i < 10; i++) {
      assertThat(limiter.tryReserve("alice", "10.0.0.3")).as("reservation %d", i).isEmpty();
    }
    assertThat(limiter.tryReserve("alice", "10.0.0.3")).isPresent();
  }

  @Test
  void idleKeysAreSweptOnceTheMapIsLarge() {
    int users = LoginAttemptLimiter.SWEEP_THRESHOLD;
    for (int i = 0; i < users; i++) {
      limiter.tryReserve("user" + i, "10.9.9.9");
    }
    assertThat(limiter.trackedKeys()).isEqualTo(users + 1);
    clock.advance(WINDOW.plusSeconds(1));

    limiter.tryReserve("fresh", "10.9.9.8");

    assertThat(limiter.trackedKeys()).isEqualTo(2);
    assertThat(limiter.retryAfter("user1", "10.9.9.9")).isEmpty();
  }

  @Test
  void clearForgetsEverything() {
    for (int i = 0; i < 10; i++) {
      limiter.tryReserve("dave", "10.0.0.9");
    }
    limiter.clear();

    assertThat(limiter.retryAfter("dave", "10.0.0.9")).isEmpty();
  }
}
