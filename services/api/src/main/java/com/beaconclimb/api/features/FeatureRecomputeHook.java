package com.beaconclimb.api.features;

import com.beaconclimb.api.attempts.AttemptWriteListener;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Recomputes the user's snapshot at {@code W = now} after each committed attempt write, in its own
 * transaction. A failure here is logged at WARN with the user id and swallowed: the attempt write
 * has already committed and must never be reported as failed because its features lag (the next
 * write or {@code make recompute-features} repairs the snapshot).
 */
@Component
public class FeatureRecomputeHook implements AttemptWriteListener {

  private static final Logger log = LoggerFactory.getLogger(FeatureRecomputeHook.class);

  private final FeatureRecomputeService features;
  private final Clock clock;

  public FeatureRecomputeHook(FeatureRecomputeService features, Clock clock) {
    this.features = features;
    this.clock = clock;
  }

  @Override
  public void afterAttemptWrite(UUID userId) {
    try {
      features.recompute(userId, clock.instant().truncatedTo(ChronoUnit.MILLIS));
    } catch (RuntimeException e) {
      log.warn("features outcome=RECOMPUTE_FAILED user_id={} error={}", userId, e.toString());
    }
  }
}
