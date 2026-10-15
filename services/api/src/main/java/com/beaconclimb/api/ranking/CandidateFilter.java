package com.beaconclimb.api.ranking;

import com.beaconclimb.api.identity.preferences.Goal;
import com.beaconclimb.api.ranking.FilterOutcome.FilterReason;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The hard filters of planning/06 section 9 in a fixed order: board, angle, ACTIVE status, the
 * request's exclusion list, then the cooldown (an attempt on the variant within {@code
 * cooldown_hours} before {@code now}) which goal PROJECT switches off.
 */
final class CandidateFilter {

  private CandidateFilter() {}

  static Optional<FilterReason> reject(Candidate candidate, UserContext context, Policy policy) {
    if (!candidate.boardId().equals(context.boardId())) {
      return Optional.of(FilterReason.INCOMPATIBLE_BOARD);
    }
    if (candidate.angleDeg() != context.angleDeg()) {
      return Optional.of(FilterReason.INCOMPATIBLE_ANGLE);
    }
    if (!Candidate.ACTIVE.equals(candidate.validationStatus())) {
      return Optional.of(FilterReason.INACTIVE);
    }
    if (context.excludeIds().contains(candidate.routeVariantId())) {
      return Optional.of(FilterReason.EXCLUDED);
    }
    if (context.goal() != Goal.PROJECT && inCooldown(candidate, context.now(), policy)) {
      return Optional.of(FilterReason.COOLDOWN);
    }
    return Optional.empty();
  }

  /** True when the last attempt is strictly after {@code now - cooldown_hours}. */
  static boolean inCooldown(Candidate candidate, Instant now, Policy policy) {
    Instant last = candidate.lastAttemptedAt();
    return last != null && last.isAfter(now.minus(Duration.ofHours(policy.cooldownHours())));
  }
}
