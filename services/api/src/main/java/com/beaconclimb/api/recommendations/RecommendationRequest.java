package com.beaconclimb.api.recommendations;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

/**
 * Raw {@code RecommendationRequest} body. Members stay JSON nodes so {@link
 * RecommendationValidator} reports every schema violation at once with contract keywords; an
 * unknown member is rejected by Jackson ({@code fail-on-unknown-properties}) as 422 UNKNOWN_FIELD.
 */
public record RecommendationRequest(
    @JsonProperty("board_id") JsonNode boardId,
    @JsonProperty("angle_deg") JsonNode angleDeg,
    JsonNode goal,
    @JsonProperty("max_routes") JsonNode maxRoutes,
    @JsonProperty("session_id") JsonNode sessionId,
    @JsonProperty("exclude_route_variant_ids") JsonNode excludeRouteVariantIds,
    @JsonProperty("policy_version") JsonNode policyVersion) {

  /** Spring MVC logs request bodies at DEBUG; raw client input never reaches the log (TM-16). */
  @Override
  public String toString() {
    return "RecommendationRequest[redacted]";
  }
}
