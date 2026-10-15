package com.beaconclimb.api.attempts;

import java.util.UUID;

/**
 * Called by {@link AttemptService} once an attempt create, correction or retraction has committed,
 * outside that transaction. Implementations run their own work (and their own transaction) and must
 * not throw; the write has already succeeded.
 */
public interface AttemptWriteListener {

  void afterAttemptWrite(UUID userId);
}
