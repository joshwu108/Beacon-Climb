package com.beaconclimb.api.identity;

import static com.beaconclimb.api.platform.jdbc.JdbcTypes.instant;
import static com.beaconclimb.api.platform.jdbc.JdbcTypes.timestamp;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code consents} access. Every statement filters by the authenticated {@code user_id} in SQL, so
 * one user's consent can never be read or changed through another user's call.
 */
@Repository
public class ConsentRepository {

  /** One consent row; {@code revokedAt} is null while the consent is active. */
  public record ConsentRecord(
      String version,
      Instant consentedAt,
      Instant revokedAt,
      String scopesJson,
      String textSha256) {

    public boolean isActive() {
      return revokedAt == null;
    }
  }

  private final JdbcClient jdbc;

  public ConsentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Latest consent of the user by acceptance time, revoked or not. */
  public Optional<ConsentRecord> findLatest(UUID userId) {
    return jdbc.sql(
            "SELECT consent_version, consented_at, revoked_at, scopes_json, text_sha256"
                + " FROM consents WHERE user_id = :user_id"
                + " ORDER BY consented_at DESC, id DESC LIMIT 1")
        .param("user_id", userId)
        .query(
            (rs, rowNum) ->
                new ConsentRecord(
                    rs.getString("consent_version"),
                    instant(rs, "consented_at"),
                    instant(rs, "revoked_at"),
                    rs.getString("scopes_json"),
                    rs.getString("text_sha256")))
        .optional();
  }

  /** True when the user holds an unrevoked consent for exactly {@code version}. */
  public boolean hasActive(UUID userId, String version) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM consents WHERE user_id = :user_id"
                + " AND consent_version = :version AND revoked_at IS NULL)")
        .param("user_id", userId)
        .param("version", version)
        .query(Boolean.class)
        .single();
  }

  /** Upsert on {@code (user_id, consent_version)}; a re-grant clears {@code revoked_at} (TM-09). */
  public void upsert(
      UUID id, UUID userId, String version, String scopesJson, String textSha256, Instant now) {
    jdbc.sql(
            "INSERT INTO consents (id, user_id, consent_version, scopes_json, text_sha256,"
                + " consented_at, revoked_at)"
                + " VALUES (:id, :user_id, :version, CAST(:scopes AS jsonb), :sha, :now, NULL)"
                + " ON CONFLICT (user_id, consent_version) DO UPDATE SET"
                + " scopes_json = EXCLUDED.scopes_json, text_sha256 = EXCLUDED.text_sha256,"
                + " consented_at = EXCLUDED.consented_at, revoked_at = NULL")
        .param("id", id)
        .param("user_id", userId)
        .param("version", version)
        .param("scopes", scopesJson)
        .param("sha", textSha256)
        .param("now", timestamp(now))
        .update();
  }

  /** Revokes every active consent of the user; returns the number of rows changed. */
  public int revokeActive(UUID userId, Instant now) {
    return jdbc.sql(
            "UPDATE consents SET revoked_at = :now"
                + " WHERE user_id = :user_id AND revoked_at IS NULL")
        .param("now", timestamp(now))
        .param("user_id", userId)
        .update();
  }
}
