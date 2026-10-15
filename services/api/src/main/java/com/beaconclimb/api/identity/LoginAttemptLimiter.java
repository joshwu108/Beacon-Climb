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
 * Either bucket being full blocks the attempt before any password hashing runs (TM-22).
 *
 * <p>State lives in this JVM only and resets on restart; BC-0122 unifies it with Bucket4j and the
 * per-user POST limit. Keys are not logged.
 */
@Component
public class LoginAttemptLimiter {

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

  /** Seconds the caller must wait when either bucket is full; empty when the attempt may run. */
  public Optional<Duration> retryAfter(String username, String clientIp) {
    Instant now = clock.instant();
    return retryAfter(USERNAME_PREFIX + username, now)
        .or(() -> retryAfter(IP_PREFIX + clientIp, now));
  }

  public void recordFailure(String username, String clientIp) {
    Instant now = clock.instant();
    record(USERNAME_PREFIX + username, now);
    record(IP_PREFIX + clientIp, now);
  }

  /** Forgets every failure; for tests sharing one application context. */
  public void clear() {
    failures.clear();
  }

  private Optional<Duration> retryAfter(String key, Instant now) {
    Deque<Instant> bucket = failures.get(key);
    if (bucket == null) {
      return Optional.empty();
    }
    synchronized (bucket) {
      prune(bucket, now);
      if (bucket.size() < limit) {
        return Optional.empty();
      }
      Duration wait = Duration.between(now, bucket.peekFirst().plus(window));
      return Optional.of(wait.isNegative() ? Duration.ZERO : wait);
    }
  }

  private void record(String key, Instant now) {
    Deque<Instant> bucket = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
    synchronized (bucket) {
      prune(bucket, now);
      bucket.addLast(now);
    }
  }

  private void prune(Deque<Instant> bucket, Instant now) {
    Instant cutoff = now.minus(window);
    while (!bucket.isEmpty() && !bucket.peekFirst().isAfter(cutoff)) {
      bucket.pollFirst();
    }
  }
}
