package com.beaconclimb.events;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUIDv7 (RFC 9562): 48-bit Unix millisecond timestamp, version 7, 12 random bits, RFC variant and
 * 62 random bits. The {@code event_id} generator for records built by this library and by the
 * stream job (the api keeps its own copy in {@code com.beaconclimb.api.identity}).
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

  public static UUID at(long epochMillis) {
    long randomA = RANDOM.nextLong() & 0x0FFFL;
    long msb = (epochMillis << 16) | VERSION_7 | randomA;
    long lsb = (RANDOM.nextLong() & VARIANT_MASK) | VARIANT_RFC;
    return new UUID(msb, lsb);
  }
}
