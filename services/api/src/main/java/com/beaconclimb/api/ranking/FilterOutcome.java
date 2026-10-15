package com.beaconclimb.api.ranking;

/**
 * A candidate removed by a hard filter and the first filter (in evaluation order) that removed it.
 */
public record FilterOutcome(String routeVariantId, FilterReason reason) {

  /** Evaluation order: board, angle, status, exclusion, cooldown. */
  public enum FilterReason {
    INCOMPATIBLE_BOARD,
    INCOMPATIBLE_ANGLE,
    INACTIVE,
    EXCLUDED,
    COOLDOWN;

    /** The contract reason code this outcome surfaces as, when it has one. */
    public ReasonCode reasonCode() {
      return this == COOLDOWN ? ReasonCode.COOLDOWN_SKIPPED : null;
    }
  }
}
