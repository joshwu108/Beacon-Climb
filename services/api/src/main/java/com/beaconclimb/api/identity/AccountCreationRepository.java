package com.beaconclimb.api.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code account_creations} (V8): one row per created account with the client address that
 * registered it, read only as a per-address count since midnight UTC ({@link AccountCreationCap}).
 * Parameterised SQL only; the address is stored as given, never logged here.
 */
@Repository
public class AccountCreationRepository {

  private final JdbcClient jdbc;

  public AccountCreationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Accounts {@code clientIp} created at or after {@code since}; uses the (ip, created_at) index.
   */
  public long countSince(String clientIp, Instant since) {
    return jdbc.sql(
            "SELECT count(*) FROM account_creations"
                + " WHERE client_ip = :ip AND created_at >= :since")
        .param("ip", clientIp)
        .param("since", Timestamp.from(since))
        .query(Long.class)
        .single();
  }

  /** Records a created account; runs inside the registration transaction. */
  public void insert(UUID userId, String clientIp, Instant createdAt) {
    jdbc.sql(
            "INSERT INTO account_creations (user_id, client_ip, created_at)"
                + " VALUES (:userId, :ip, :createdAt)")
        .param("userId", userId)
        .param("ip", clientIp)
        .param("createdAt", Timestamp.from(createdAt))
        .update();
  }
}
