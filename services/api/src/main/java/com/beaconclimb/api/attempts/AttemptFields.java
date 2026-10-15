package com.beaconclimb.api.attempts;

import com.beaconclimb.api.platform.json.Jcs;
import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.FieldError;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/**
 * Member-level validation shared by {@code AttemptCreate} and {@code AttemptPatch} (contract
 * 1.1.0). Each check appends one {@link FieldError} with the JSON Schema keyword it mirrors and
 * returns null for an absent or invalid member. Numbers follow JSON Schema semantics: {@code 2.0}
 * is a valid integer (ADR-0002 JCS: {@code 1}, {@code 1.0} and {@code 1e0} are the same value).
 * {@code completion_fraction} is rounded to the three decimals the column keeps so the stored body
 * equals what a later read returns.
 */
final class AttemptFields {

  static final int MAX_NOTES_LENGTH = 500;
  static final int MIN_EFFORT = 1;
  static final int MAX_EFFORT = 10;
  static final int FRACTION_SCALE = 3;

  private AttemptFields() {}

  /** Strict parse (duplicate keys, trailing data and non-JSON rejected); must be an object. */
  static JsonNode parseObject(String rawBody) {
    if (rawBody == null || rawBody.isBlank()) {
      throw ApiException.validationFailed(
          List.of(new FieldError("body", "required", "request body is required")));
    }
    JsonNode node;
    try {
      node = Jcs.parse(rawBody);
    } catch (Jcs.JcsException e) {
      throw ApiException.validationFailed(
          List.of(new FieldError("body", "malformed", "request body is not valid JSON")));
    }
    if (!node.isObject()) {
      throw ApiException.validationFailed(
          List.of(new FieldError("body", "type", "must be object")));
    }
    return node;
  }

  /** 422 UNKNOWN_FIELD naming every member outside {@code allowed} (ADR-0002, BC-0109-IDS). */
  static void rejectUnknownMembers(JsonNode body, Set<String> allowed) {
    List<FieldError> errors = new ArrayList<>();
    for (Map.Entry<String, JsonNode> member : body.properties()) {
      if (!allowed.contains(member.getKey())) {
        errors.add(new FieldError(member.getKey(), "additionalProperties", "unknown field"));
      }
    }
    if (!errors.isEmpty()) {
      throw new ApiException(
          ErrorCode.UNKNOWN_FIELD,
          HttpStatus.UNPROCESSABLE_CONTENT,
          "Request contains an unknown field",
          errors);
    }
  }

  static boolean isMissing(JsonNode node) {
    return node == null || node.isMissingNode();
  }

  static Integer attemptsCount(JsonNode node, List<FieldError> errors) {
    Integer value = integer("attempts_count", node, errors);
    if (value != null && value < 1) {
      errors.add(new FieldError("attempts_count", "minimum", "must be >= 1"));
      return null;
    }
    return value;
  }

  static Boolean sent(JsonNode node, List<FieldError> errors) {
    if (!node.isBoolean()) {
      errors.add(new FieldError("sent", "type", "must be boolean"));
      return null;
    }
    return node.booleanValue();
  }

  static Double completionFraction(JsonNode node, List<FieldError> errors) {
    if (!node.isNumber()) {
      errors.add(new FieldError("completion_fraction", "type", "must be number"));
      return null;
    }
    BigDecimal value = node.decimalValue();
    if (value.signum() < 0) {
      errors.add(new FieldError("completion_fraction", "minimum", "must be >= 0"));
      return null;
    }
    if (value.compareTo(BigDecimal.ONE) > 0) {
      errors.add(new FieldError("completion_fraction", "maximum", "must be <= 1"));
      return null;
    }
    return value.setScale(FRACTION_SCALE, RoundingMode.HALF_UP).doubleValue();
  }

  static Integer perceivedEffort(JsonNode node, List<FieldError> errors) {
    Integer value = integer("perceived_effort", node, errors);
    if (value == null) {
      return null;
    }
    if (value < MIN_EFFORT) {
      errors.add(new FieldError("perceived_effort", "minimum", "must be >= " + MIN_EFFORT));
      return null;
    }
    if (value > MAX_EFFORT) {
      errors.add(new FieldError("perceived_effort", "maximum", "must be <= " + MAX_EFFORT));
      return null;
    }
    return value;
  }

  static String notes(JsonNode node, List<FieldError> errors) {
    if (!node.isString()) {
      errors.add(new FieldError("notes", "type", "must be string"));
      return null;
    }
    String value = node.stringValue();
    if (value.codePointCount(0, value.length()) > MAX_NOTES_LENGTH) {
      errors.add(
          new FieldError(
              "notes", "maxLength", "must be at most " + MAX_NOTES_LENGTH + " characters"));
      return null;
    }
    return value;
  }

  /** JSON Schema integer: any number with a zero fractional part, within the int range. */
  static Integer integer(String field, JsonNode node, List<FieldError> errors) {
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
}
