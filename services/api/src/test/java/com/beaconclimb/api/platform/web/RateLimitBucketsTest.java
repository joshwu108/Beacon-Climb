package com.beaconclimb.api.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.platform.web.RateLimitBuckets.Decision;
import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Token-bucket semantics, Retry-After rounding and idle eviction with a controllable clock. */
class RateLimitBucketsTest {

  private final AtomicLong nanos = new AtomicLong(Duration.ofHours(1).toNanos());
  private final TimeMeter clock =
      new TimeMeter() {
        @Override
        public long currentTimeNanos() {
          return nanos.get();
        }

        @Override
        public boolean isWallClockBased() {
          return false;
        }
      };

  @Test
  void burstOfLimitIsAllowedThenRefusedUntilRefill() {
    RateLimitBuckets buckets = new RateLimitBuckets(3, 100, clock);

    assertThat(buckets.tryConsume("k")).isEqualTo(new Decision(true, 2, 0));
    assertThat(buckets.tryConsume("k")).isEqualTo(new Decision(true, 1, 0));
    assertThat(buckets.tryConsume("k")).isEqualTo(new Decision(true, 0, 0));
    Decision refused = buckets.tryConsume("k");
    assertThat(refused.allowed()).isFalse();
    assertThat(refused.retryAfterSeconds()).isEqualTo(20);
    assertThat(buckets.tryConsume("other").allowed()).isTrue();

    nanos.addAndGet(Duration.ofSeconds(20).toNanos());

    assertThat(buckets.tryConsume("k")).isEqualTo(new Decision(true, 0, 0));
  }

  @Test
  void idleBucketsAreEvictedOnceTheMapIsFull() {
    RateLimitBuckets buckets = new RateLimitBuckets(5, 2, clock);
    buckets.tryConsume("a");
    buckets.tryConsume("b");
    assertThat(buckets.size()).isEqualTo(2);

    nanos.addAndGet(Duration.ofSeconds(61).toNanos());
    buckets.tryConsume("c");

    assertThat(buckets.size()).isEqualTo(1);
    assertThat(buckets.tryConsume("a").remaining()).isEqualTo(4);
  }

  /** BC-0123 F1: the public bucket holds a small burst and refills at the larger rate. */
  @Test
  void smallBurstRefillsAtTheConfiguredRate() {
    RateLimitBuckets buckets = new RateLimitBuckets(2, 30, 100, clock);

    assertThat(buckets.tryConsume("k")).isEqualTo(new Decision(true, 1, 0));
    assertThat(buckets.tryConsume("k")).isEqualTo(new Decision(true, 0, 0));
    Decision refused = buckets.tryConsume("k");
    assertThat(refused.allowed()).isFalse();
    assertThat(refused.retryAfterSeconds()).isEqualTo(2);

    nanos.addAndGet(Duration.ofSeconds(2).toNanos());

    assertThat(buckets.tryConsume("k")).isEqualTo(new Decision(true, 0, 0));
  }

  @Test
  void aBucketThatNeedsSeveralPeriodsToRefillIsNotEvictedAfterOne() {
    RateLimitBuckets buckets = new RateLimitBuckets(10, 5, 1, clock);
    for (int i = 0; i < 10; i++) {
      buckets.tryConsume("a");
    }

    nanos.addAndGet(Duration.ofSeconds(61).toNanos());
    buckets.tryConsume("b");

    assertThat(buckets.size()).isEqualTo(2);
    assertThat(buckets.tryConsume("a").remaining()).isEqualTo(4);
  }

  @Test
  void retryAfterRoundsUpToWholeSecondsAndIsAtLeastOne() {
    assertThat(RateLimitBuckets.ceilSeconds(0)).isEqualTo(1);
    assertThat(RateLimitBuckets.ceilSeconds(1)).isEqualTo(1);
    assertThat(RateLimitBuckets.ceilSeconds(1_000_000_000L)).isEqualTo(1);
    assertThat(RateLimitBuckets.ceilSeconds(1_000_000_001L)).isEqualTo(2);
  }
}
