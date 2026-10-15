package com.beaconclimb.api.recommendations.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/** {@code RecommendationResponse}: {@code feature_as_of} is never null (effective as-of). */
public record RecommendationResponse(
    @JsonProperty("decision_id") UUID decisionId,
    @JsonProperty("policy_version") String policyVersion,
    @JsonProperty("model_version") String modelVersion,
    @JsonProperty("feature_as_of") String featureAsOf,
    @JsonProperty("cold_start") boolean coldStart,
    List<RecommendationView> recommendations,
    @JsonProperty("fallback_used") boolean fallbackUsed) {

  public RecommendationResponse {
    recommendations = List.copyOf(recommendations);
  }
}
