package com.beaconclimb.api.platform.web;

import com.beaconclimb.api.platform.web.RateLimitBuckets.Decision;
import org.springframework.stereotype.Component;

/**
 * The two write limiters of ADR-0005 / BC-0122: per authenticated user ({@code
 * beacon.http.write-limit-per-minute}) and per client address for the unauthenticated public POSTs
 * ({@code beacon.http.public-write-limit-per-minute}). State lives in this JVM only and resets on
 * restart; a multi-instance deployment needs a shared store, which is out of scope for the demo.
 */
@Component
public class WriteRateLimiter {

  private final RateLimitBuckets users;
  private final RateLimitBuckets clients;

  public WriteRateLimiter(HttpProperties properties) {
    this.users =
        new RateLimitBuckets(properties.writeLimitPerMinute(), properties.maxTrackedClients());
    this.clients =
        new RateLimitBuckets(
            properties.publicWriteLimitPerMinute(), properties.maxTrackedClients());
  }

  public Decision tryUser(String userId) {
    return users.tryConsume(userId);
  }

  public Decision tryClient(String clientAddress) {
    return clients.tryConsume(clientAddress);
  }

  /** Forgets every bucket; for tests sharing one application context. */
  public void clear() {
    users.clear();
    clients.clear();
  }
}
