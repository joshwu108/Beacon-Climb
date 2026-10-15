package com.beaconclimb.api.attempts;

import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.FieldError;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * {@code AttemptPatch} schema validation (contract 1.1.0): unknown members are 422 UNKNOWN_FIELD;
 * an empty object is 422 (nothing to change); {@code retract} must be {@code true} and cannot be
 * combined with a correction, because a retraction is its own revision (ADR-0002).
 */
final class AttemptPatchValidator {

  static final Set<String> MEMBERS =
      Set.of(
          "attempts_count", "sent", "completion_fraction", "perceived_effort", "notes", "retract");

  private AttemptPatchValidator() {}

  static AttemptPatch validate(JsonNode body) {
    AttemptFields.rejectUnknownMembers(body, MEMBERS);
    List<FieldError> errors = new ArrayList<>();
    if (body.isEmpty()) {
      errors.add(new FieldError("body", "minProperties", "must contain at least one member"));
    }
    boolean retract = retract(body.get("retract"), errors);
    if (retract && body.size() > 1) {
      errors.add(new FieldError("retract", "exclusive", "cannot be combined with other members"));
    }
    Integer attemptsCount =
        AttemptCreateValidator.optional(
            body, "attempts_count", errors, AttemptFields::attemptsCount);
    Boolean sent = AttemptCreateValidator.optional(body, "sent", errors, AttemptFields::sent);
    Double fraction =
        AttemptCreateValidator.optional(
            body, "completion_fraction", errors, AttemptFields::completionFraction);
    Integer effort =
        AttemptCreateValidator.optional(
            body, "perceived_effort", errors, AttemptFields::perceivedEffort);
    String notes = AttemptCreateValidator.optional(body, "notes", errors, AttemptFields::notes);
    if (!errors.isEmpty()) {
      throw ApiException.validationFailed(errors);
    }
    return new AttemptPatch(attemptsCount, sent, fraction, effort, notes, retract);
  }

  private static boolean retract(JsonNode node, List<FieldError> errors) {
    if (AttemptFields.isMissing(node)) {
      return false;
    }
    if (!node.isBoolean()) {
      errors.add(new FieldError("retract", "type", "must be boolean"));
      return false;
    }
    if (!node.booleanValue()) {
      errors.add(new FieldError("retract", "const", "must be true when present"));
      return false;
    }
    return true;
  }
}
