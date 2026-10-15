package com.beaconclimb.api.identity.preferences;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

/**
 * Raw {@code Preferences} request body. Members are kept as JSON nodes so {@link
 * PreferencesValidator} can report every schema violation at once with contract-style keywords
 * (required, type, enum, maxItems, uniqueItems) instead of Jackson failing on the first one.
 * Unknown members are still rejected by Jackson ({@code fail-on-unknown-properties}) as 422
 * UNKNOWN_FIELD.
 */
public record PreferencesRequest(
    @JsonProperty("board_id") JsonNode boardId,
    @JsonProperty("angle_deg") JsonNode angleDeg,
    @JsonProperty("initial_grade_band") JsonNode initialGradeBand,
    JsonNode goals) {

  /** Spring MVC logs request bodies at DEBUG; raw client input never reaches the log (TM-16). */
  @Override
  public String toString() {
    return "PreferencesRequest[redacted]";
  }
}
