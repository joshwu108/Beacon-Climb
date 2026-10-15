package com.beaconclimb.api.identity.preferences;

import static com.beaconclimb.api.platform.jdbc.JdbcTypes.timestamp;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code user_preferences} access plus the one catalog read the validation needs. Every statement
 * on {@code user_preferences} filters by the authenticated {@code user_id} in SQL, so one user's
 * row can never be read or changed through another user's call. {@code privacy_settings_json} is
 * untouched here (reserved for the privacy ticket).
 */
@Repository
public class PreferencesRepository {

  private static final TypeReference<List<Goal>> GOALS = new TypeReference<>() {};
  private static final TypeReference<List<Integer>> ANGLES = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final JsonMapper json;

  public PreferencesRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public Optional<PreferencesView> find(UUID userId) {
    return jdbc.sql(
            "SELECT board_id, angle_deg, initial_grade_band, goals_json::text AS goals_json"
                + " FROM user_preferences WHERE user_id = :user_id")
        .param("user_id", userId)
        .query(
            (rs, rowNum) ->
                new PreferencesView(
                    rs.getString("board_id"),
                    rs.getInt("angle_deg"),
                    GradeBand.valueOf(rs.getString("initial_grade_band")),
                    json.readValue(rs.getString("goals_json"), GOALS)))
        .optional();
  }

  /** Insert or replace the user's single row; {@code updated_at} is always {@code now}. */
  public void upsert(UUID userId, PreferencesView preferences, Instant now) {
    jdbc.sql(
            "INSERT INTO user_preferences (user_id, board_id, angle_deg, initial_grade_band,"
                + " goals_json, updated_at)"
                + " VALUES (:user_id, :board_id, :angle, :band, CAST(:goals AS jsonb), :now)"
                + " ON CONFLICT (user_id) DO UPDATE SET board_id = EXCLUDED.board_id,"
                + " angle_deg = EXCLUDED.angle_deg,"
                + " initial_grade_band = EXCLUDED.initial_grade_band,"
                + " goals_json = EXCLUDED.goals_json, updated_at = EXCLUDED.updated_at")
        .param("user_id", userId)
        .param("board_id", preferences.boardId())
        .param("angle", preferences.angleDeg())
        .param("band", preferences.initialGradeBand().name())
        .param("goals", json.writeValueAsString(preferences.goals()))
        .param("now", timestamp(now))
        .update();
  }

  /** The board's available angles; empty when the board does not exist. */
  public Optional<List<Integer>> availableAngles(String boardId) {
    return jdbc.sql(
            "SELECT available_angles_json::text AS angles FROM boards WHERE board_id = :board_id")
        .param("board_id", boardId)
        .query((rs, rowNum) -> json.readValue(rs.getString("angles"), ANGLES))
        .optional();
  }
}
