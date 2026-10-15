package com.beaconclimb.api.ranking;

import java.util.List;

/** One served position: 1-based, with the score and the reason codes in contract order. */
public record Ranked(
    String routeVariantId,
    int position,
    double score,
    List<ReasonCode> reasonCodes,
    ScoreInputs inputs) {

  public Ranked {
    reasonCodes = List.copyOf(reasonCodes);
  }
}
