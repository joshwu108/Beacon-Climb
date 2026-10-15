package com.beaconclimb.api.recommendations;

import com.beaconclimb.api.identity.preferences.Goal;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A shape-valid {@code RecommendationRequest}. {@link #toJsonNode()} is the normalised contract
 * form stored as {@code request_json} and returned by {@code getDecision}; {@link #toConstraints()}
 * is the {@code request_constraints} member of the decision record (the same minus {@code
 * session_id}, which the record carries at top level).
 */
public record ValidatedRequest(
    String boardId,
    int angleDeg,
    Goal goal,
    int maxRoutes,
    UUID sessionId,
    List<String> excludeRouteVariantIds) {

  public ValidatedRequest {
    excludeRouteVariantIds = List.copyOf(excludeRouteVariantIds);
  }

  public JsonNode toJsonNode() {
    ObjectNode node = toConstraints();
    node.put("session_id", sessionId.toString());
    return node;
  }

  public ObjectNode toConstraints() {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("board_id", boardId);
    node.put("angle_deg", angleDeg);
    node.put("goal", goal.name());
    node.put("max_routes", maxRoutes);
    ArrayNode exclude = node.putArray("exclude_route_variant_ids");
    excludeRouteVariantIds.forEach(exclude::add);
    return node;
  }
}
