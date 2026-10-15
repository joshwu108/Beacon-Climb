package com.beaconclimb.api.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * In-memory sliding-window login limiter (ADR-0005): at most {@code beacon.auth.login-limit} failed
 * attempts per {@code beacon.auth.login-window} per username and, independently, per client IP.
 *
 * <p>The failure slot is reserved atomically before the password is hashed ({@link #tryReserve})
 * and given back on success ({@link #release}), so parallel requests cannot all pass the check
 * before any failure is recorded (BC-0123 F11). Either bucket being full refuses the attempt before
 * any hashing runs (TM-22). Keys idle for longer than the window are swept once the map holds more
 * than {@value #SWEEP_THRESHOLD} keys; a reservation racing with that sweep can at worst be
 * forgotten, never counted twice. State lives in this JVM only and resets on restart. Keys are not
 * logged.
 */
@Component
public class LoginAttemptLimiter {

  static final int SWEEP_THRESHOLD = 10_000;
  private static final String USERNAME_PREFIX = "u:";
  private static final String IP_PREFIX = "ip:";

  private final ConcurrentHashMap<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
  private final int limit;
  private final Duration window;
  private final Clock clock;

  @Autowired
  public LoginAttemptLimiter(AuthProperties properties, Clock clock) {
    this(properties.loginLimit(), properties.loginWindow(), clock);
  }

  LoginAttemptLimiter(int limit, Duration window, Clock clock) {
    this.limit = limit;
    this.window = window;
    this.clock = clock;
  }

  /**
   * Reserves one failure slot in both buckets, or returns how long the caller must wait when either
   * bucket is full (nothing is reserved then). Locks are taken username first, then address, so the
   * two prefixed key spaces can never deadlock.
   */
  public Optional<Duration> tryReserve(String username, String clientIp) {
    Instant now = clock.instant();
    sweepIfLarge(now);
    Deque<Instant> byUser = bucket(USERNAME_PREFIX + username);
    Deque<Instant> byIp = bucket(IP_PREFIX + clientIp);
    synchronized (byUser) {
      synchronized (byIp) {
        Optional<Duration> wait = retryAfter(byUser, now).or(() -> retryAfter(byIp, now));
        if (wait.isPresent()) {
          return wait;
        }
        byUser.addLast(now);
        byIp.addLast(now);
        return Optional.empty();
      }
    }
  }

  /** Gives back the slot of a successful login; the latest reservation in each bucket. */
  public void release(String username, String clientIp) {
    releaseLatest(USERNAME_PREFIX + username);
    releaseLatest(IP_PREFIX + clientIp);
  }

  /** Seconds the caller must wait when either bucket is full; empty when an attempt may run. */
  public Optional<Duration> retryAfter(String username, String clientIp) {
    Instant now = clock.instant();
    return retryAfter(USERNAME_PREFIX + username, now)
        .or(() -> retryAfter(IP_PREFIX + clientIp, now));
  }

  /** Forgets every failure; for tests sharing one application context. */
  public void clear() {
    failures.clear();
  }

  int trackedKeys() {
    return failures.size();
  }

  private Deque<Instant> bucket(String key) {
    return failures.computeIfAbsent(key, k -> new ArrayDeque<>());
  }

  private Optional<Duration> retryAfter(String key, Instant now) {
    Deque<Instant> bucket = failures.get(key);
    if (bucket == null) {
      return Optional.empty();
    }
    synchronized (bucket) {
      return retryAfter(bucket, now);
    }
  }

  /** Caller holds the bucket's monitor. */
  private Optional<Duration> retryAfter(Deque<Instant> bucket, Instant now) {
    prune(bucket, now);
    if (bucket.size() < limit) {
      return Optional.empty();
    }
    Duration wait = Duration.between(now, bucket.peekFirst().plus(window));
    return Optional.of(wait.isNegative() ? Duration.ZERO : wait);
  }

  private void releaseLatest(String key) {
    Deque<Instant> bucket = failures.get(key);
    if (bucket == null) {
      return;
    }
    synchronized (bucket) {
      bucket.pollLast();
    }
  }

  private void prune(Deque<Instant> bucket, Instant now) {
    Instant cutoff = now.minus(window);
    while (!bucket.isEmpty() && !bucket.peekFirst().isAfter(cutoff)) {
      bucket.pollFirst();
    }
  }

  /** Drops keys whose every failure has left the window; only when the map has grown large. */
  private void sweepIfLarge(Instant now) {
    if (failures.size() <= SWEEP_THRESHOLD) {
      return;
    }
    failures
        .entrySet()
        .removeIf(
            entry -> {
              Deque<Instant> bucket = entry.getValue();
              synchronized (bucket) {
                prune(bucket, now);
                return bucket.isEmpty();
              }
            });
  }
}
