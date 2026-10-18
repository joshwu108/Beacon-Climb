package com.beaconclimb.api.recommendations.decisions;

import com.beaconclimb.api.features.FeatureSource;
import com.beaconclimb.api.recommendations.api.RecommendationView;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * {@code Decision}: the served decision as the contract exposes it. {@code request} is the stored,
 * validated {@code RecommendationRequest}. Never carries the snapshot URI, the digests, the user
 * features or the policy configuration (contract: "Never includes the candidate snapshot URI or
 * user features"). {@code features_source} and {@code features_cutoff_w} (contract 1.2.0, BC-0310)
 * mirror the recommendation response; the cutoff is omitted when none was recorded.
 */
public record DecisionView(
    @JsonProperty("decision_id") UUID decisionId,
    @JsonProperty("created_at") String createdAt,
    JsonNode request,
    @JsonProperty("policy_version") String policyVersion,
    @JsonProperty("model_version") String modelVersion,
    @JsonProperty("feature_as_of") String featureAsOf,
    @JsonProperty("features_source") FeatureSource featuresSource,
    @JsonProperty("features_cutoff_w") @JsonInclude(JsonInclude.Include.NON_NULL)
        String featuresCutoffW,
    @JsonProperty("cold_start") boolean coldStart,
    @JsonProperty("fallback_used") boolean fallbackUsed,
    List<RecommendationView> recommendations) {

  public DecisionView {
    recommendations = List.copyOf(recommendations);
  }
}
