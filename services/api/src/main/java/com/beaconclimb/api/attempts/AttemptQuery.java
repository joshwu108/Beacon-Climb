package com.beaconclimb.api.attempts;

/**
 * Validated {@code listAttempts} parameters. A null status means both statuses; a null cursor means
 * the first page. {@code limit} is 1..100 (default 20).
 */
public record AttemptQuery(AttemptStatus status, AttemptCursor cursor, int limit) {

  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 100;

  public AttemptQuery {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be 1.." + MAX_LIMIT);
    }
  }

  public static AttemptQuery firstPage(AttemptStatus status, int limit) {
    return new AttemptQuery(status, null, limit);
  }

  public AttemptQuery withCursor(AttemptCursor next) {
    return new AttemptQuery(status, next, limit);
  }
}
