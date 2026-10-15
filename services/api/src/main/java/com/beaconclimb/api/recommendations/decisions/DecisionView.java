package com.beaconclimb.api.recommendations.decisions;

import com.beaconclimb.api.recommendations.api.RecommendationView;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * {@code Decision}: the served decision as the contract exposes it. {@code request} is the stored,
 * validated {@code RecommendationRequest}. Never carries the snapshot URI, the digests, the user
 * features or the policy configuration (contract: "Never includes the candidate snapshot URI or
 * user features").
 */
public record DecisionView(
    @JsonProperty("decision_id") UUID decisionId,
    @JsonProperty("created_at") String createdAt,
    JsonNode request,
    @JsonProperty("policy_version") String policyVersion,
    @JsonProperty("model_version") String modelVersion,
    @JsonProperty("feature_as_of") String featureAsOf,
    @JsonProperty("cold_start") boolean coldStart,
    @JsonProperty("fallback_used") boolean fallbackUsed,
    List<RecommendationView> recommendations) {

  public DecisionView {
    recommendations = List.copyOf(recommendations);
  }
}
