package com.beaconclimb.api.identity;

import static com.beaconclimb.api.platform.jdbc.JdbcTypes.timestamp;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code sessions} access keyed by sha256(token). A session resolves only while it is unrevoked,
 * unexpired and its user is ACTIVE; all three predicates live in SQL (ADR-0005, TM-04/TM-21).
 */
@Repository
public class SessionRepository {

  private final JdbcClient jdbc;

  public SessionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(String tokenHash, UUID userId, Instant createdAt, Instant expiresAt) {
    jdbc.sql(
            "INSERT INTO sessions (token_hash, user_id, created_at, expires_at)"
                + " VALUES (:token_hash, :user_id, :created_at, :expires_at)")
        .param("token_hash", tokenHash)
        .param("user_id", userId)
        .param("created_at", timestamp(createdAt))
        .param("expires_at", timestamp(expiresAt))
        .update();
  }

  public Optional<AuthenticatedUser> findActive(String tokenHash, Instant now) {
    return jdbc.sql(
            "SELECT u.id, u.username FROM sessions s JOIN users u ON u.id = s.user_id"
                + " WHERE s.token_hash = :token_hash AND s.revoked_at IS NULL"
                + " AND s.expires_at > :now AND u.status = 'ACTIVE'")
        .param("token_hash", tokenHash)
        .param("now", timestamp(now))
        .query(
            (rs, rowNum) ->
                new AuthenticatedUser(
                    rs.getObject("id", UUID.class), rs.getString("username"), tokenHash))
        .optional();
  }

  /** Marks the session revoked; returns the number of rows changed (0 if already revoked). */
  public int revoke(String tokenHash, Instant now) {
    return jdbc.sql(
            "UPDATE sessions SET revoked_at = :now"
                + " WHERE token_hash = :token_hash AND revoked_at IS NULL")
        .param("now", timestamp(now))
        .param("token_hash", tokenHash)
        .update();
  }
}
