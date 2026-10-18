package com.beaconclimb.api.recommendations.api;

import com.beaconclimb.api.features.FeatureSource;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/**
 * {@code RecommendationResponse}: {@code feature_as_of} is never null (effective as-of). {@code
 * features_source} and {@code features_cutoff_w} (contract 1.2.0, BC-0310) say which snapshot row
 * the ranking read; the cutoff is omitted, never null, when the server recorded none.
 */
public record RecommendationResponse(
    @JsonProperty("decision_id") UUID decisionId,
    @JsonProperty("policy_version") String policyVersion,
    @JsonProperty("model_version") String modelVersion,
    @JsonProperty("feature_as_of") String featureAsOf,
    @JsonProperty("features_source") FeatureSource featuresSource,
    @JsonProperty("features_cutoff_w") @JsonInclude(JsonInclude.Include.NON_NULL)
        String featuresCutoffW,
    @JsonProperty("cold_start") boolean coldStart,
    List<RecommendationView> recommendations,
    @JsonProperty("fallback_used") boolean fallbackUsed) {

  public RecommendationResponse {
    recommendations = List.copyOf(recommendations);
  }
}
