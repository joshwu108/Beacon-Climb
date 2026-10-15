package com.beaconclimb.api.ranking;

import java.util.ArrayList;
import java.util.List;

/**
 * The outcome of one {@link Ranker#rank}: the served list, every hard-filter rejection (cooldown
 * rejections are dropped from here when the low-inventory widening re-admitted them), and the two
 * result-level flags. {@link #resultReasons()} is the result-level reason set a decision record
 * carries beside the per-candidate codes.
 */
public record RankResult(
    List<Ranked> ranked,
    List<FilterOutcome> rejected,
    boolean lowInventoryWidened,
    boolean coldStart) {

  public RankResult {
    ranked = List.copyOf(ranked);
    rejected = List.copyOf(rejected);
  }

  public List<ReasonCode> resultReasons() {
    List<ReasonCode> reasons = new ArrayList<>();
    if (rejected.stream().anyMatch(r -> r.reason() == FilterOutcome.FilterReason.COOLDOWN)) {
      reasons.add(ReasonCode.COOLDOWN_SKIPPED);
    }
    if (lowInventoryWidened) {
      reasons.add(ReasonCode.LOW_INVENTORY_WIDENED);
    }
    if (coldStart) {
      reasons.add(ReasonCode.COLD_START_HEURISTIC);
    }
    return List.copyOf(reasons);
  }
}
