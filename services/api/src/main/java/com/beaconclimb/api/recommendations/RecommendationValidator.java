package com.beaconclimb.api.recommendations;

import com.beaconclimb.api.catalog.RouteVariantId;
import com.beaconclimb.api.identity.preferences.Goal;
import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.FieldError;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * Schema validation of a {@link RecommendationRequest} against {@code
 * components.schemas.RecommendationRequest} (contract 1.1.0): six required members, {@code
 * max_routes} an integer in 1..20, {@code session_id} a canonical UUID, {@code
 * exclude_route_variant_ids} at most 100 public route variant ids. One {@link FieldError} per
 * violation with the JSON Schema keyword it mirrors; the board and angle lookups happen in {@link
 * RecommendationService} once the shape is right. Numbers follow JSON Schema semantics ({@code 4.0}
 * is a valid integer).
 */
final class RecommendationValidator {

  static final int MIN_ROUTES = 1;
  static final int MAX_ROUTES = 20;
  static final int MAX_EXCLUDED = 100;
  static final List<String> POLICY_VERSIONS = List.of("heuristic-v1", "heuristic-v2");
  private static final Pattern UUID_CANONICAL =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  private RecommendationValidator() {}

  /** The validated request, or 422 VALIDATION_FAILED listing every violation. */
  static ValidatedRequest validate(RecommendationRequest request) {
    List<FieldError> errors = new ArrayList<>();
    String boardId = boardId(request.boardId(), errors);
    Integer angle = integer("angle_deg", request.angleDeg(), errors);
    Goal goal = goal(request.goal(), errors);
    Integer maxRoutes = maxRoutes(request.maxRoutes(), errors);
    UUID sessionId = sessionId(request.sessionId(), errors);
    List<String> exclude = exclude(request.excludeRouteVariantIds(), errors);
    String policyVersion = policyVersion(request.policyVersion(), errors);
    if (!errors.isEmpty()) {
      throw ApiException.validationFailed(errors);
    }
    return new ValidatedRequest(boardId, angle, goal, maxRoutes, sessionId, exclude, policyVersion);
  }

  /** Optional (1.3.0): absent or null means the server default. */
  private static String policyVersion(JsonNode node, List<FieldError> errors) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isString()) {
      errors.add(new FieldError("policy_version", "type", "must be string"));
      return null;
    }
    String value = node.asString();
    if (!POLICY_VERSIONS.contains(value)) {
      errors.add(
          new FieldError(
              "policy_version", "enum", "must be one of " + String.join(", ", POLICY_VERSIONS)));
      return null;
    }
    return value;
  }

  private static String boardId(JsonNode node, List<FieldError> errors) {
    if (isMissing(node)) {
      errors.add(new FieldError("board_id", "required", "is required"));
      return null;
    }
    if (!node.isString() || node.stringValue().isBlank()) {
      errors.add(new FieldError("board_id", "type", "must be string"));
      return null;
    }
    return node.stringValue();
  }

  private static Goal goal(JsonNode node, List<FieldError> errors) {
    if (isMissing(node)) {
      errors.add(new FieldError("goal", "required", "is required"));
      return null;
    }
    if (!node.isString()) {
      errors.add(new FieldError("goal", "type", "must be string"));
      return null;
    }
    return Arrays.stream(Goal.values())
        .filter(goal -> goal.name().equals(node.stringValue()))
        .findFirst()
        .orElseGet(
            () -> {
              String allowed =
                  Arrays.stream(Goal.values()).map(Enum::name).collect(Collectors.joining(", "));
              errors.add(new FieldError("goal", "enum", "must be one of " + allowed));
              return null;
            });
  }

  private static Integer maxRoutes(JsonNode node, List<FieldError> errors) {
    Integer value = integer("max_routes", node, errors);
    if (value == null) {
      return null;
    }
    if (value < MIN_ROUTES) {
      errors.add(new FieldError("max_routes", "minimum", "must be >= " + MIN_ROUTES));
      return null;
    }
    if (value > MAX_ROUTES) {
      errors.add(new FieldError("max_routes", "maximum", "must be <= " + MAX_ROUTES));
      return null;
    }
    return value;
  }

  private static UUID sessionId(JsonNode node, List<FieldError> errors) {
    if (isMissing(node)) {
      errors.add(new FieldError("session_id", "required", "is required"));
      return null;
    }
    if (!node.isString()) {
      errors.add(new FieldError("session_id", "type", "must be string"));
      return null;
    }
    if (!UUID_CANONICAL.matcher(node.stringValue()).matches()) {
      errors.add(new FieldError("session_id", "format", "must be a uuid"));
      return null;
    }
    return UUID.fromString(node.stringValue());
  }

  private static List<String> exclude(JsonNode node, List<FieldError> errors) {
    if (isMissing(node)) {
      errors.add(new FieldError("exclude_route_variant_ids", "required", "is required"));
      return List.of();
    }
    if (!node.isArray()) {
      errors.add(new FieldError("exclude_route_variant_ids", "type", "must be array"));
      return List.of();
    }
    if (node.size() > MAX_EXCLUDED) {
      errors.add(
          new FieldError(
              "exclude_route_variant_ids",
              "maxItems",
              "must have at most " + MAX_EXCLUDED + " items"));
    }
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < node.size(); i++) {
      JsonNode item = node.get(i);
      String field = "exclude_route_variant_ids[" + i + "]";
      if (!item.isString()) {
        errors.add(new FieldError(field, "type", "must be string"));
      } else if (RouteVariantId.parse(item.stringValue()).isEmpty()) {
        errors.add(new FieldError(field, "pattern", "must be <provider_route_uuid>:<angle_deg>"));
      } else {
        ids.add(item.stringValue());
      }
    }
    return ids;
  }

  /** JSON Schema integer: any number with a zero fractional part, within the int range. */
  private static Integer integer(String field, JsonNode node, List<FieldError> errors) {
    if (isMissing(node)) {
      errors.add(new FieldError(field, "required", "is required"));
      return null;
    }
    if (!node.isNumber()) {
      errors.add(new FieldError(field, "type", "must be integer"));
      return null;
    }
    BigDecimal value = node.decimalValue();
    if (value.stripTrailingZeros().scale() > 0) {
      errors.add(new FieldError(field, "type", "must be integer"));
      return null;
    }
    if (value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0
        || value.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0) {
      errors.add(new FieldError(field, "maximum", "is outside the supported integer range"));
      return null;
    }
    return value.intValueExact();
  }

  private static boolean isMissing(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode();
  }
}
