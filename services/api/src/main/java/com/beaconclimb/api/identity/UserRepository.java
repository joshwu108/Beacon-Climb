package com.beaconclimb.api.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code users} access; parameterised SQL only. Never returns the password hash to callers. */
@Repository
public class UserRepository {

  /** Credentials row for login; the hash stays inside the identity package. */
  public record Credentials(UUID id, String username, String passwordHash) {}

  private final JdbcClient jdbc;

  public UserRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts a new ACTIVE user with role USER.
   *
   * @throws DuplicateKeyException when the username is already registered
   */
  public void insert(UUID id, String username, String passwordHash) {
    jdbc.sql("INSERT INTO users (id, username, password_hash) VALUES (:id, :username, :hash)")
        .param("id", id)
        .param("username", username)
        .param("hash", passwordHash)
        .update();
  }

  public Optional<Credentials> findActiveByUsername(String username) {
    return jdbc.sql(
            "SELECT id, username, password_hash FROM users"
                + " WHERE username = :username AND status = 'ACTIVE'")
        .param("username", username)
        .query(
            (rs, rowNum) ->
                new Credentials(
                    rs.getObject("id", UUID.class),
                    rs.getString("username"),
                    rs.getString("password_hash")))
        .optional();
  }
}
