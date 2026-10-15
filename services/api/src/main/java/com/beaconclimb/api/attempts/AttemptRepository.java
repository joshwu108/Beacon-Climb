package com.beaconclimb.api.attempts;

import static com.beaconclimb.api.platform.jdbc.JdbcTypes.instant;
import static com.beaconclimb.api.platform.jdbc.JdbcTypes.timestamp;

import com.beaconclimb.api.catalog.CatalogQueryRepository.VariantRow;
import com.beaconclimb.api.catalog.CatalogService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code attempts} access (BC-0109). Every statement is parameterised and every read or write of a
 * row carries the owner's {@code user_id} in the same SQL predicate (TM-02), so another user's
 * attempt is indistinguishable from a missing one. Reads join the route summary in one query.
 */
@Repository
public class AttemptRepository {

  private static final String SELECT =
      "SELECT a.attempt_id, a.user_id, a.variant_id, a.route_variant_public_id, a.occurred_at,"
          + " a.received_at, a.attempts_count, a.sent, a.completion_fraction, a.perceived_effort,"
          + " a.notes, a.revision, a.status, a.request_fingerprint,"
          + " a.response_body_json::text AS response_body_json, r.provider_route_uuid,"
          + " r.board_id, v.angle_deg, r.name, v.grade_numeric, v.grade_observation_count"
          + " FROM attempts a JOIN route_variants v ON v.variant_id = a.variant_id"
          + " JOIN routes r ON r.route_id = v.route_id";

  private final JdbcClient jdbc;

  public AttemptRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * {@code INSERT ... ON CONFLICT (user_id, submission_idempotency_key) DO NOTHING} (ADR-0002).
   * False when the key already exists for this user; the caller re-reads the committed row.
   */
  public boolean insertIfAbsent(
      AttemptRow row, String idempotencyKey, String fingerprint, String responseBodyJson) {
    return jdbc.sql(
            "INSERT INTO attempts (attempt_id, user_id, variant_id, route_variant_public_id,"
                + " occurred_at, received_at, attempts_count, sent, completion_fraction,"
                + " perceived_effort, notes, revision, status, submission_idempotency_key,"
                + " request_fingerprint, response_body_json, updated_at)"
                + " VALUES (:id, :user_id, :variant_id, :public_id, :occurred_at, :received_at,"
                + " :count, :sent, :fraction, :effort, :notes, :revision, :status, :key,"
                + " :fingerprint, CAST(:body AS jsonb), :received_at)"
                + " ON CONFLICT (user_id, submission_idempotency_key) DO NOTHING"
                + " RETURNING attempt_id")
        .param("id", row.attemptId())
        .param("user_id", row.userId())
        .param("variant_id", row.variantId())
        .param("public_id", row.routeVariantId())
        .param("occurred_at", timestamp(row.occurredAt()))
        .param("received_at", timestamp(row.receivedAt()))
        .param("count", row.attemptsCount())
        .param("sent", row.sent())
        .param("fraction", fraction(row.completionFraction()))
        .param("effort", row.perceivedEffort())
        .param("notes", row.notes())
        .param("revision", row.revision())
        .param("status", row.status().name())
        .param("key", idempotencyKey)
        .param("fingerprint", fingerprint)
        .param("body", responseBodyJson)
        .query(UUID.class)
        .optional()
        .isPresent();
  }

  public Optional<AttemptRow> findByKey(UUID userId, String idempotencyKey) {
    return jdbc.sql(SELECT + " WHERE a.user_id = :user_id AND a.submission_idempotency_key = :key")
        .param("user_id", userId)
        .param("key", idempotencyKey)
        .query(AttemptRepository::row)
        .optional();
  }

  public Optional<AttemptRow> findOwned(UUID userId, UUID attemptId) {
    return jdbc.sql(SELECT + " WHERE a.user_id = :user_id AND a.attempt_id = :id")
        .param("user_id", userId)
        .param("id", attemptId)
        .query(AttemptRepository::row)
        .optional();
  }

  /**
   * Applies {@code next} only if the row still is at {@code expectedRevision} and belongs to the
   * owner; 0 rows means a revision conflict (or a vanished row), never a partial write.
   */
  public int updateIfRevision(AttemptRow next, int expectedRevision, Instant now) {
    return jdbc.sql(
            "UPDATE attempts SET attempts_count = :count, sent = :sent,"
                + " completion_fraction = :fraction, perceived_effort = :effort, notes = :notes,"
                + " status = :status, revision = revision + 1, updated_at = :now"
                + " WHERE attempt_id = :id AND user_id = :user_id AND revision = :revision")
        .param("count", next.attemptsCount())
        .param("sent", next.sent())
        .param("fraction", fraction(next.completionFraction()))
        .param("effort", next.perceivedEffort())
        .param("notes", next.notes())
        .param("status", next.status().name())
        .param("now", timestamp(now))
        .param("id", next.attemptId())
        .param("user_id", next.userId())
        .param("revision", expectedRevision)
        .update();
  }

  /** Up to {@code limit + 1} rows newest first after the cursor; the extra row signals a page. */
  public List<AttemptRow> list(UUID userId, AttemptQuery query) {
    StringBuilder sql = new StringBuilder(SELECT).append(" WHERE a.user_id = :user_id");
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("user_id", userId);
    if (query.status() != null) {
      sql.append(" AND a.status = :status");
      params.put("status", query.status().name());
    }
    if (query.cursor() != null) {
      sql.append(
          " AND (a.occurred_at < :cursor_at"
              + " OR (a.occurred_at = :cursor_at AND a.attempt_id < :cursor_id))");
      params.put("cursor_at", timestamp(query.cursor().occurredAt()));
      params.put("cursor_id", query.cursor().attemptId());
    }
    sql.append(" ORDER BY a.occurred_at DESC, a.attempt_id DESC LIMIT :limit");
    params.put("limit", query.limit() + 1);
    return jdbc.sql(sql.toString()).params(params).query(AttemptRepository::row).list();
  }

  private static BigDecimal fraction(Double value) {
    return value == null
        ? null
        : BigDecimal.valueOf(value).setScale(AttemptFields.FRACTION_SCALE, RoundingMode.HALF_UP);
  }

  private static AttemptRow row(ResultSet rs, int rowNum) throws SQLException {
    UUID variantId = rs.getObject("variant_id", UUID.class);
    VariantRow variant =
        new VariantRow(
            variantId,
            rs.getString("provider_route_uuid"),
            rs.getString("board_id"),
            rs.getInt("angle_deg"),
            rs.getString("name"),
            rs.getBigDecimal("grade_numeric"),
            rs.getInt("grade_observation_count"),
            null);
    BigDecimal fraction = rs.getBigDecimal("completion_fraction");
    int effort = rs.getInt("perceived_effort");
    boolean effortNull = rs.wasNull();
    return new AttemptRow(
        rs.getObject("attempt_id", UUID.class),
        rs.getObject("user_id", UUID.class),
        variantId,
        rs.getString("route_variant_public_id"),
        instant(rs, "occurred_at"),
        instant(rs, "received_at"),
        rs.getInt("attempts_count"),
        rs.getBoolean("sent"),
        fraction == null ? null : fraction.doubleValue(),
        effortNull ? null : effort,
        rs.getString("notes"),
        rs.getInt("revision"),
        AttemptStatus.valueOf(rs.getString("status")),
        rs.getString("request_fingerprint"),
        rs.getString("response_body_json"),
        CatalogService.summary(variant));
  }
}
