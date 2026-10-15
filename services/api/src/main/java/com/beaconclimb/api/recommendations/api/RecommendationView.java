package com.beaconclimb.api.recommendations.api;

import com.beaconclimb.api.catalog.api.RouteVariantSummaryView;
import com.beaconclimb.api.ranking.ReasonCode;
import com.beaconclimb.api.recommendations.UncertaintyLabel;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * {@code Recommendation}: one served position. In the MVP {@code predicted_send_probability} is
 * always null with {@code INSUFFICIENT_HISTORY} (no model, planning/06 section 10); the member is
 * still serialised because the contract requires it.
 */
public record RecommendationView(
    @JsonProperty("route_variant_id") String routeVariantId,
    RouteVariantSummaryView route,
    int position,
    @JsonProperty("rank_score") double rankScore,
    @JsonProperty("reason_codes") List<ReasonCode> reasonCodes,
    @JsonProperty("predicted_send_probability") Double predictedSendProbability,
    @JsonProperty("uncertainty_label") UncertaintyLabel uncertaintyLabel) {

  public RecommendationView {
    reasonCodes = List.copyOf(reasonCodes);
  }
}
