package com.beaconclimb.api.attempts;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The application {@link Clock} for the attempts tests: live UTC time unless a test pins it with
 * {@link #fix(Instant)} (BC-0109-TIME), released again by {@link #reset()}.
 */
public final class TestClock extends Clock {

  private final AtomicReference<Instant> fixed = new AtomicReference<>();

  public void fix(Instant at) {
    fixed.set(at);
  }

  public void reset() {
    fixed.set(null);
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    Instant pinned = fixed.get();
    return pinned != null ? pinned : Instant.now();
  }
}
