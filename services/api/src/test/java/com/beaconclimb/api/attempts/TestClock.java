package com.beaconclimb.api.attempts;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The application {@link Clock} for the attempts tests: live UTC time unless a test pins it with
 * {@link #fix(Instant)} (BC-0109-TIME) or makes it strictly increasing with {@link #tick()}, both
 * released again by {@link #reset()}.
 *
 * <p>Ticking mode (BC-0124c): every read returns the live time truncated to milliseconds, or one
 * millisecond past the previous read when that would not be later, so two readers can never observe
 * the same millisecond. It is linearisable: a read that happens later returns a larger instant. The
 * feature hook derives {@code cutoff_w} from this clock, so the mode makes concurrent hooks'
 * cutoffs distinct and totally ordered by when they were taken.
 */
public final class TestClock extends Clock {

  private final AtomicReference<Instant> fixed = new AtomicReference<>();
  private final AtomicReference<Instant> lastTick = new AtomicReference<>();

  public void fix(Instant at) {
    fixed.set(at);
  }

  public void tick() {
    lastTick.set(Instant.EPOCH);
  }

  public void reset() {
    fixed.set(null);
    lastTick.set(null);
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
    if (pinned != null) {
      return pinned;
    }
    if (lastTick.get() == null) {
      return Instant.now();
    }
    return lastTick.updateAndGet(
        previous -> {
          Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
          return previous == null || now.isAfter(previous) ? now : previous.plusMillis(1);
        });
  }
}
