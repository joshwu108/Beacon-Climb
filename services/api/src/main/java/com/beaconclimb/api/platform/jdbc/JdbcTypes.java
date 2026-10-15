package com.beaconclimb.api.platform.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * JDBC mapping helpers for the java.time types pgjdbc does not bind natively. {@code timestamptz}
 * columns are bound and read as UTC {@link OffsetDateTime} and exposed as {@link Instant}.
 */
public final class JdbcTypes {

  private JdbcTypes() {}

  public static OffsetDateTime timestamp(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  public static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
