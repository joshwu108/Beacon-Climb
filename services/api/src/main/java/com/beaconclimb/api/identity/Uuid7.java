package com.beaconclimb.api.identity;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * UUIDv7 (RFC 9562): 48-bit Unix millisecond timestamp, 4-bit version 7, 12 random bits, 2-bit
 * variant 10 and 62 random bits. Server-generated primary ids per ADR-0002; no library dependency.
 */
public final class Uuid7 {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final long VERSION_7 = 0x7000L;
  private static final long VARIANT_MASK = 0x3FFF_FFFF_FFFF_FFFFL;
  private static final long VARIANT_RFC = 0x8000_0000_0000_0000L;

  private Uuid7() {}

  public static UUID next() {
    return at(System.currentTimeMillis());
  }

  public static UUID next(Clock clock) {
    return at(clock.millis());
  }

  static UUID at(long epochMillis) {
    long randomA = RANDOM.nextLong() & 0x0FFFL;
    long msb = (epochMillis << 16) | VERSION_7 | randomA;
    long lsb = (RANDOM.nextLong() & VARIANT_MASK) | VARIANT_RFC;
    return new UUID(msb, lsb);
  }
}
