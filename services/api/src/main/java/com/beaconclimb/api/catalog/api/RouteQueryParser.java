package com.beaconclimb.api.catalog.api;

import com.beaconclimb.api.catalog.RouteCursor;
import com.beaconclimb.api.catalog.RouteQuery;
import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.FieldError;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Turns the raw {@code listRoutes} query parameters into a {@link RouteQuery}. Type and range
 * failures are collected into one 422 VALIDATION_FAILED whose {@code field_errors} name the
 * parameter and the validator keyword (contracts/examples/listRoutes/response-422.json); an
 * undecodable cursor is 422 INVALID_CURSOR.
 */
final class RouteQueryParser {

  /** Raw query parameters exactly as received; null when absent. */
  record RawQuery(
      String boardId,
      String angleDeg,
      String gradeMin,
      String gradeMax,
      String cursor,
      String limit) {}

  private static final Pattern INTEGER = Pattern.compile("-?[0-9]{1,9}");
  private static final Pattern NUMBER = Pattern.compile("-?[0-9]{1,9}(\\.[0-9]{1,6})?");
  private static final int MAX_BOARD_ID_LENGTH = 128;

  private RouteQueryParser() {}

  static RouteQuery parse(RawQuery raw) {
    List<FieldError> errors = new ArrayList<>();
    String boardId = boardId(raw.boardId(), errors);
    Integer angle = integer("angle_deg", raw.angleDeg(), errors);
    BigDecimal gradeMin = number("grade_min", raw.gradeMin(), errors);
    BigDecimal gradeMax = number("grade_max", raw.gradeMax(), errors);
    int limit = limit(raw.limit(), errors);
    if (!errors.isEmpty()) {
      throw ApiException.validationFailed(errors);
    }
    return new RouteQuery(boardId, angle, gradeMin, gradeMax, cursor(raw.cursor()), limit);
  }

  private static String boardId(String value, List<FieldError> errors) {
    if (value == null) {
      return null;
    }
    if (value.isBlank() || value.length() > MAX_BOARD_ID_LENGTH) {
      errors.add(new FieldError("board_id", "pattern", "must be a board id"));
      return null;
    }
    return value;
  }

  private static Integer integer(String field, String value, List<FieldError> errors) {
    if (value == null) {
      return null;
    }
    if (!INTEGER.matcher(value).matches()) {
      errors.add(new FieldError(field, "type", "must be an integer"));
      return null;
    }
    return Integer.valueOf(value);
  }

  private static BigDecimal number(String field, String value, List<FieldError> errors) {
    if (value == null) {
      return null;
    }
    if (!NUMBER.matcher(value).matches()) {
      errors.add(new FieldError(field, "type", "must be a number"));
      return null;
    }
    return new BigDecimal(value);
  }

  private static int limit(String value, List<FieldError> errors) {
    Integer parsed = integer("limit", value, errors);
    if (parsed == null) {
      return RouteQuery.DEFAULT_LIMIT;
    }
    if (parsed < 1) {
      errors.add(new FieldError("limit", "minimum", "must be >= 1"));
      return RouteQuery.DEFAULT_LIMIT;
    }
    if (parsed > RouteQuery.MAX_LIMIT) {
      errors.add(new FieldError("limit", "maximum", "must be <= " + RouteQuery.MAX_LIMIT));
      return RouteQuery.DEFAULT_LIMIT;
    }
    return parsed;
  }

  private static RouteCursor cursor(String value) {
    if (value == null) {
      return null;
    }
    Optional<RouteCursor> decoded = RouteCursor.decode(value);
    if (decoded.isEmpty()) {
      throw new ApiException(
          ErrorCode.INVALID_CURSOR, HttpStatus.UNPROCESSABLE_CONTENT, "Cursor is not valid");
    }
    return decoded.get();
  }
}
