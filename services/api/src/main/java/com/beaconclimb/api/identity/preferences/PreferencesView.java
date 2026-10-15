package com.beaconclimb.api.identity.preferences;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * {@code Preferences}: exactly the four contract members ({@code additionalProperties: false}). The
 * backlog row mentions returning {@code updated_at}, but the frozen 1.1.0 schema has no such
 * member, so it stays in the table only.
 */
public record PreferencesView(
    @JsonProperty("board_id") String boardId,
    @JsonProperty("angle_deg") int angleDeg,
    @JsonProperty("initial_grade_band") GradeBand initialGradeBand,
    List<Goal> goals) {

  public PreferencesView {
    goals = List.copyOf(goals);
  }
}
