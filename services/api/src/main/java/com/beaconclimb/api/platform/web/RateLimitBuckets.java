package com.beaconclimb.api.platform.web;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One Bucket4j token bucket per key (user id or client address): {@code limit} tokens of capacity,
 * refilled greedily at {@code limit} per minute, so a burst of {@code limit} requests is allowed
 * and the next one waits for the next token. Buckets live in a {@link ConcurrentHashMap}; once the
 * map exceeds {@code maxTracked} entries, buckets idle for a full refill period are evicted, which
 * can never grant extra tokens because an idle bucket is already full. Keys are never logged.
 */
public final class RateLimitBuckets {

  /** Outcome of one request: allowed with tokens left, or refused with the wait in seconds. */
  public record Decision(boolean allowed, long remaining, long retryAfterSeconds) {}

  static final Duration REFILL_PERIOD = Duration.ofMinutes(1);
  private static final long NANOS_PER_SECOND = 1_000_000_000L;

  private record Entry(Bucket bucket, AtomicLong lastUsedNanos) {}

  private final Map<String, Entry> buckets = new ConcurrentHashMap<>();
  private final int limit;
  private final int maxTracked;
  private final TimeMeter timeMeter;

  public RateLimitBuckets(int limit, int maxTracked) {
    this(limit, maxTracked, TimeMeter.SYSTEM_MILLISECONDS);
  }

  RateLimitBuckets(int limit, int maxTracked, TimeMeter timeMeter) {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be >= 1");
    }
    this.limit = limit;
    this.maxTracked = maxTracked;
    this.timeMeter = timeMeter;
  }

  public Decision tryConsume(String key) {
    long now = timeMeter.currentTimeNanos();
    if (buckets.size() >= maxTracked) {
      evictIdle(now);
    }
    Entry entry = buckets.computeIfAbsent(key, k -> new Entry(newBucket(), new AtomicLong(now)));
    entry.lastUsedNanos().set(now);
    ConsumptionProbe probe = entry.bucket().tryConsumeAndReturnRemaining(1);
    if (probe.isConsumed()) {
      return new Decision(true, probe.getRemainingTokens(), 0);
    }
    return new Decision(false, 0, ceilSeconds(probe.getNanosToWaitForRefill()));
  }

  /** Forgets every bucket; for tests sharing one application context. */
  public void clear() {
    buckets.clear();
  }

  int size() {
    return buckets.size();
  }

  private Bucket newBucket() {
    return Bucket.builder()
        .addLimit(bandwidth -> bandwidth.capacity(limit).refillGreedy(limit, REFILL_PERIOD))
        .withCustomTimePrecision(timeMeter)
        .build();
  }

  private void evictIdle(long now) {
    long cutoff = now - REFILL_PERIOD.toNanos();
    buckets.entrySet().removeIf(entry -> entry.getValue().lastUsedNanos().get() <= cutoff);
  }

  static long ceilSeconds(long nanos) {
    return Math.max(1, (nanos + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND);
  }
}
