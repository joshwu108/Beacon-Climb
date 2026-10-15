package com.beaconclimb.api.identity.preferences;

import com.beaconclimb.api.platform.web.FieldError;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * Schema validation of a {@link PreferencesRequest} against {@code components.schemas.Preferences}
 * (contract 1.1.0): all four members required, {@code board_id} string, {@code angle_deg} integer,
 * {@code initial_grade_band} a {@link GradeBand}, {@code goals} an array of at most four unique
 * {@link Goal}s. Returns one {@link FieldError} per violation; the board and angle lookups happen
 * in {@link PreferencesService} once the shape is right.
 */
final class PreferencesValidator {

  static final int MAX_GOALS = 4;

  /** Shape-valid request, ready for the board lookup. */
  record Validated(String boardId, int angleDeg, GradeBand initialGradeBand, List<Goal> goals) {}

  private PreferencesValidator() {}

  static List<FieldError> validate(PreferencesRequest request) {
    List<FieldError> errors = new ArrayList<>();
    validateBoardId(request.boardId(), errors);
    validateAngle(request.angleDeg(), errors);
    validateGradeBand(request.initialGradeBand(), errors);
    validateGoals(request.goals(), errors);
    return List.copyOf(errors);
  }

  /** Only valid after {@link #validate} returned no errors. */
  static Validated toValidated(PreferencesRequest request) {
    List<Goal> goals = new ArrayList<>();
    for (JsonNode goal : request.goals()) {
      goals.add(Goal.valueOf(goal.asString()));
    }
    return new Validated(
        request.boardId().asString(),
        request.angleDeg().asInt(),
        GradeBand.valueOf(request.initialGradeBand().asString()),
        List.copyOf(goals));
  }

  private static void validateBoardId(JsonNode node, List<FieldError> errors) {
    if (isMissing(node)) {
      errors.add(new FieldError("board_id", "required", "is required"));
    } else if (!node.isString() || node.asString().isBlank()) {
      errors.add(new FieldError("board_id", "type", "must be string"));
    }
  }

  private static void validateAngle(JsonNode node, List<FieldError> errors) {
    if (isMissing(node)) {
      errors.add(new FieldError("angle_deg", "required", "is required"));
    } else if (!node.isIntegralNumber() || !node.canConvertToInt()) {
      errors.add(new FieldError("angle_deg", "type", "must be integer"));
    }
  }

  private static void validateGradeBand(JsonNode node, List<FieldError> errors) {
    if (isMissing(node)) {
      errors.add(new FieldError("initial_grade_band", "required", "is required"));
    } else if (!node.isString()) {
      errors.add(new FieldError("initial_grade_band", "type", "must be string"));
    } else if (!isOneOf(GradeBand.values(), node.asString())) {
      errors.add(
          new FieldError(
              "initial_grade_band", "enum", "must be one of " + names(GradeBand.values())));
    }
  }

  private static void validateGoals(JsonNode node, List<FieldError> errors) {
    if (isMissing(node)) {
      errors.add(new FieldError("goals", "required", "is required"));
      return;
    }
    if (!node.isArray()) {
      errors.add(new FieldError("goals", "type", "must be array"));
      return;
    }
    if (node.size() > MAX_GOALS) {
      errors.add(new FieldError("goals", "maxItems", "must have at most " + MAX_GOALS + " items"));
    }
    Set<String> seen = new HashSet<>();
    boolean duplicate = false;
    for (int i = 0; i < node.size(); i++) {
      JsonNode goal = node.get(i);
      String field = "goals[" + i + "]";
      if (!goal.isString()) {
        errors.add(new FieldError(field, "type", "must be string"));
      } else if (!isOneOf(Goal.values(), goal.asString())) {
        errors.add(new FieldError(field, "enum", "must be one of " + names(Goal.values())));
      } else if (!seen.add(goal.asString())) {
        duplicate = true;
      }
    }
    if (duplicate) {
      errors.add(new FieldError("goals", "uniqueItems", "must not contain duplicates"));
    }
  }

  private static boolean isMissing(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode();
  }

  private static boolean isOneOf(Enum<?>[] values, String candidate) {
    return Arrays.stream(values).anyMatch(value -> value.name().equals(candidate));
  }

  private static String names(Enum<?>[] values) {
    return Arrays.stream(values).map(Enum::name).collect(Collectors.joining(", "));
  }
}
