package com.beaconclimb.features;

import java.util.UUID;

/**
 * UUIDv7-shaped ids for tests (version 7, RFC variant) so {@code UUID.compareTo} orders them as the
 * contract's 128-bit comparison does: random ones for the ported computer tests, numbered ones for
 * the golden fixtures, which must be reproducible byte for byte.
 */
final class TestIds {

  private static final long VERSION_7 = 0x7000L;
  private static final long VARIANT_MASK = 0x3FFF_FFFF_FFFF_FFFFL;
  private static final long VARIANT_RFC = 0x8000_0000_0000_0000L;

  private TestIds() {}

  /** A fresh v7 id at the current millisecond with random low bits. */
  static UUID uuid7() {
    long millis = System.currentTimeMillis();
    long randomA = java.util.concurrent.ThreadLocalRandom.current().nextLong() & 0x0FFFL;
    long msb = (millis << 16) | VERSION_7 | randomA;
    long lsb =
        (java.util.concurrent.ThreadLocalRandom.current().nextLong() & VARIANT_MASK) | VARIANT_RFC;
    return new UUID(msb, lsb);
  }

  /** The deterministic id {@code 018f0000-0000-7000-8000-<n as 12 hex digits>}. */
  static UUID numbered(long n) {
    return UUID.fromString(String.format("018f0000-0000-7000-8000-%012x", n));
  }
}
