package com.beaconclimb.api.attempts.api;

import com.beaconclimb.api.attempts.AttemptCursor;
import com.beaconclimb.api.attempts.AttemptQuery;
import com.beaconclimb.api.attempts.AttemptStatus;
import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.FieldError;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Turns the raw {@code listAttempts} query parameters into an {@link AttemptQuery}. Type, range and
 * enum failures are one 422 VALIDATION_FAILED naming the parameter and the validator keyword; an
 * undecodable cursor is 422 INVALID_CURSOR (contract listAttempts 422 codes).
 */
final class AttemptQueryParser {

  private static final Pattern INTEGER = Pattern.compile("-?[0-9]{1,9}");

  private AttemptQueryParser() {}

  static AttemptQuery parse(String status, String cursor, String limit) {
    List<FieldError> errors = new ArrayList<>();
    AttemptStatus parsedStatus = status(status, errors);
    int parsedLimit = limit(limit, errors);
    if (!errors.isEmpty()) {
      throw ApiException.validationFailed(errors);
    }
    return new AttemptQuery(parsedStatus, cursor(cursor), parsedLimit);
  }

  private static AttemptStatus status(String value, List<FieldError> errors) {
    if (value == null) {
      return null;
    }
    for (AttemptStatus candidate : AttemptStatus.values()) {
      if (candidate.name().equals(value)) {
        return candidate;
      }
    }
    errors.add(new FieldError("status", "enum", "must be one of ACTIVE, RETRACTED"));
    return null;
  }

  private static int limit(String value, List<FieldError> errors) {
    if (value == null) {
      return AttemptQuery.DEFAULT_LIMIT;
    }
    if (!INTEGER.matcher(value).matches()) {
      errors.add(new FieldError("limit", "type", "must be an integer"));
      return AttemptQuery.DEFAULT_LIMIT;
    }
    int parsed = Integer.parseInt(value);
    if (parsed < 1) {
      errors.add(new FieldError("limit", "minimum", "must be >= 1"));
      return AttemptQuery.DEFAULT_LIMIT;
    }
    if (parsed > AttemptQuery.MAX_LIMIT) {
      errors.add(new FieldError("limit", "maximum", "must be <= " + AttemptQuery.MAX_LIMIT));
      return AttemptQuery.DEFAULT_LIMIT;
    }
    return parsed;
  }

  private static AttemptCursor cursor(String value) {
    if (value == null) {
      return null;
    }
    return AttemptCursor.decode(value)
        .orElseThrow(
            () ->
                new ApiException(
                    ErrorCode.INVALID_CURSOR,
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "Cursor is not valid"));
  }
}
