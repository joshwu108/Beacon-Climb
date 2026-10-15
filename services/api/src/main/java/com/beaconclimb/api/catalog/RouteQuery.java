package com.beaconclimb.api.catalog;

import java.math.BigDecimal;

/**
 * Validated {@code listRoutes} filters. Every member but {@code limit} is optional; {@code boardId}
 * null means all boards. A grade bound excludes ungraded variants (NULL compares to nothing); an
 * angle outside a board's available angles just yields an empty page.
 */
public record RouteQuery(
    String boardId,
    Integer angleDeg,
    BigDecimal gradeMin,
    BigDecimal gradeMax,
    RouteCursor cursor,
    int limit) {

  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 100;

  public RouteQuery {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit out of range: " + limit);
    }
  }

  public RouteQuery withCursor(RouteCursor next) {
    return new RouteQuery(boardId, angleDeg, gradeMin, gradeMax, next, limit);
  }
}
