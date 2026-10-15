package com.beaconclimb.api.ranking;

import com.beaconclimb.api.identity.preferences.Goal;
import com.beaconclimb.api.identity.preferences.GradeBand;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The request side of one ranking: who asks, for which board and angle, with which goal and band,
 * plus the frozen feature values the policy reads ({@code recentVariantIds} and {@code
 * maxSentGradeNumeric90d} from climber-features.v1). {@code now} is the request instant the
 * cooldown is measured from. A user with no recent variants and no graded send is cold start.
 */
public record UserContext(
    UUID userId,
    UUID sessionId,
    String boardId,
    int angleDeg,
    Goal goal,
    GradeBand gradeBand,
    List<String> recentVariantIds,
    Set<String> excludeIds,
    Double maxSentGradeNumeric90d,
    Instant now) {

  public UserContext {
    recentVariantIds = List.copyOf(recentVariantIds);
    excludeIds = Set.copyOf(excludeIds);
  }

  public boolean isColdStart() {
    return recentVariantIds.isEmpty() && maxSentGradeNumeric90d == null;
  }
}
