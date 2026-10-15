package com.beaconclimb.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** UUIDv7 layout: 48-bit millisecond timestamp, version 7, RFC variant, random tail (ADR-0002). */
class Uuid7Test {

  @Test
  void encodesTimestampVersionAndVariant() {
    long millis = 1_760_000_000_000L;

    UUID id = Uuid7.at(millis);

    assertThat(id.version()).isEqualTo(7);
    assertThat(id.variant()).isEqualTo(2);
    assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(millis);
  }

  @Test
  void consecutiveIdsAreTimeOrderedAndUnique() {
    Set<UUID> seen = new HashSet<>();
    long previous = Long.MIN_VALUE;
    for (int i = 0; i < 1_000; i++) {
      UUID id = Uuid7.next();
      long timestamp = id.getMostSignificantBits() >>> 16;
      assertThat(timestamp).isGreaterThanOrEqualTo(previous);
      previous = timestamp;
      assertThat(seen.add(id)).isTrue();
    }
  }
}
