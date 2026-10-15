package com.beaconclimb.api.ranking;

/**
 * The closed reason-code list of contracts/openapi.yaml {@code ReasonCode} and the decision record
 * (planning/06 section 9). Declaration order is the order codes appear in a ranked result.
 */
public enum ReasonCode {
  COMPATIBLE_ANGLE,
  GOAL_CHALLENGE_MATCH,
  NOVEL_ROUTE,
  PREFERRED_STYLE,
  WELL_OBSERVED_GRADE,
  COOLDOWN_SKIPPED,
  LOW_INVENTORY_WIDENED,
  COLD_START_HEURISTIC
}
