package com.beaconclimb.api.ranking;

import java.time.Instant;
import java.util.List;

/**
 * One route variant as the ranker sees it. {@code gradeNumeric} is null for an ungraded variant;
 * {@code validationStatus} is the catalog value ({@code ACTIVE} is the only eligible one); {@code
 * lastAttemptedAt} is the user's most recent attempt on this variant or null when never attempted
 * (the cooldown filter derives from it and the policy's {@code cooldown_hours}).
 */
public record Candidate(
    String routeVariantId,
    String boardId,
    int angleDeg,
    Double gradeNumeric,
    int gradeObservationCount,
    String validationStatus,
    List<String> tags,
    Instant lastAttemptedAt) {

  public static final String ACTIVE = "ACTIVE";

  public Candidate {
    tags = List.copyOf(tags);
  }
}
