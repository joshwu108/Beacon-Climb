package com.beaconclimb.features;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/** ADR-0002: responses carry RFC 3339 timestamps normalised to UTC with a {@code Z} offset. */
public final class Timestamps {

  private Timestamps() {}

  public static String format(Instant instant) {
    return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant);
  }
}
