package com.beaconclimb.api.attempts;

import com.beaconclimb.api.catalog.RouteVariantId;
import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.FieldError;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * {@code AttemptCreate} schema validation. Unknown members (including a client-supplied {@code
 * attempt_id}, ADR-0002 / BC-0109-IDS) are 422 UNKNOWN_FIELD before anything else; every other
 * violation is collected into one 422 VALIDATION_FAILED. {@code occurred_at} must be an RFC 3339
 * date-time with an explicit offset; it is normalised to UTC here and the five-minute skew rule is
 * applied by the service against the received time.
 */
final class AttemptCreateValidator {

  static final Set<String> MEMBERS =
      Set.of(
          "route_variant_id",
          "occurred_at",
          "attempts_count",
          "sent",
          "completion_fraction",
          "perceived_effort",
          "notes");
  private static final List<String> REQUIRED =
      List.of("route_variant_id", "occurred_at", "attempts_count", "sent");

  private AttemptCreateValidator() {}

  static AttemptCreate validate(JsonNode body) {
    AttemptFields.rejectUnknownMembers(body, MEMBERS);
    List<FieldError> errors = new ArrayList<>();
    for (String member : REQUIRED) {
      if (AttemptFields.isMissing(body.get(member))) {
        errors.add(new FieldError(member, "required", "is required"));
      }
    }
    Optional<RouteVariantId> variantId = routeVariantId(body.get("route_variant_id"), errors);
    Instant occurredAt = occurredAt(body.get("occurred_at"), errors);
    Integer attemptsCount = optional(body, "attempts_count", errors, AttemptFields::attemptsCount);
    Boolean sent = optional(body, "sent", errors, AttemptFields::sent);
    Double fraction =
        optional(body, "completion_fraction", errors, AttemptFields::completionFraction);
    Integer effort = optional(body, "perceived_effort", errors, AttemptFields::perceivedEffort);
    String notes = optional(body, "notes", errors, AttemptFields::notes);
    if (!errors.isEmpty()) {
      throw ApiException.validationFailed(errors);
    }
    return new AttemptCreate(
        variantId.orElseThrow(),
        body.get("route_variant_id").stringValue(),
        occurredAt,
        attemptsCount,
        sent,
        fraction,
        effort,
        notes);
  }

  private static Optional<RouteVariantId> routeVariantId(JsonNode node, List<FieldError> errors) {
    if (AttemptFields.isMissing(node)) {
      return Optional.empty();
    }
    if (!node.isString()) {
      errors.add(new FieldError("route_variant_id", "type", "must be string"));
      return Optional.empty();
    }
    Optional<RouteVariantId> parsed = RouteVariantId.parse(node.stringValue());
    if (parsed.isEmpty()) {
      errors.add(
          new FieldError(
              "route_variant_id", "pattern", "must be <provider_route_uuid>:<angle_deg>"));
    }
    return parsed;
  }

  /** RFC 3339 with an explicit offset ({@code Z} or {@code +hh:mm}), truncated to milliseconds. */
  static Instant occurredAt(JsonNode node, List<FieldError> errors) {
    if (AttemptFields.isMissing(node)) {
      return null;
    }
    if (!node.isString()) {
      errors.add(new FieldError("occurred_at", "type", "must be string"));
      return null;
    }
    try {
      return OffsetDateTime.parse(node.stringValue(), DateTimeFormatter.ISO_OFFSET_DATE_TIME)
          .toInstant()
          .truncatedTo(ChronoUnit.MILLIS);
    } catch (DateTimeParseException e) {
      errors.add(
          new FieldError(
              "occurred_at", "format", "must be an RFC 3339 date-time with an explicit offset"));
      return null;
    }
  }

  interface MemberCheck<T> {
    T apply(JsonNode node, List<FieldError> errors);
  }

  static <T> T optional(
      JsonNode body, String member, List<FieldError> errors, MemberCheck<T> check) {
    JsonNode node = body.get(member);
    return AttemptFields.isMissing(node) ? null : check.apply(node, errors);
  }
}
