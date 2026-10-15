package com.beaconclimb.api.identity;

import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;

/**
 * Bounds how many Argon2 hashes run at once (BC-0123 F1, TM-22/TM-25). Every hash reserves 64 MiB
 * of heap for its duration, so an unbounded number of parallel login or registration requests runs
 * the JVM out of memory. The gate is a fair semaphore: a caller waits at most {@code wait} for a
 * permit and is otherwise refused with 503 SERVICE_UNAVAILABLE and {@code Retry-After: 1}, which
 * costs no memory. {@link #maxObservedConcurrency()} is evidence for tests only.
 */
final class HashGate {

  static final String RETRY_AFTER_SECONDS = "1";

  private final Semaphore permits;
  private final Duration wait;
  private final AtomicInteger active = new AtomicInteger();
  private final AtomicInteger maxObserved = new AtomicInteger();

  HashGate(int maxConcurrent, Duration wait) {
    if (maxConcurrent < 1) {
      throw new IllegalArgumentException("maxConcurrent must be >= 1");
    }
    this.permits = new Semaphore(maxConcurrent, true);
    this.wait = wait;
  }

  /** Acquires a permit or throws the 503; every successful call must be paired with release. */
  void acquire() {
    boolean acquired;
    try {
      acquired = permits.tryAcquire(wait.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      acquired = false;
    }
    if (!acquired) {
      throw overloaded();
    }
    int now = active.incrementAndGet();
    maxObserved.accumulateAndGet(now, Math::max);
  }

  void release() {
    active.decrementAndGet();
    permits.release();
  }

  /** The largest number of hashes that ever ran at the same time through this gate. */
  int maxObservedConcurrency() {
    return maxObserved.get();
  }

  private static ApiException overloaded() {
    return new ApiException(
        ErrorCode.SERVICE_UNAVAILABLE,
        HttpStatus.SERVICE_UNAVAILABLE,
        "Sign-in is busy; retry shortly",
        List.of(),
        Map.of("Retry-After", RETRY_AFTER_SECONDS));
  }
}
