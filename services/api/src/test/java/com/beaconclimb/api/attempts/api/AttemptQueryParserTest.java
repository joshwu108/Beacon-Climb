package com.beaconclimb.api.attempts.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beaconclimb.api.attempts.AttemptCursor;
import com.beaconclimb.api.attempts.AttemptQuery;
import com.beaconclimb.api.attempts.AttemptStatus;
import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.FieldError;
import java.time.Instant;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** {@link AttemptQueryParser}: defaults, a full set of parameters and the listAttempts 422s. */
class AttemptQueryParserTest {

  @Test
  void absentParametersGiveTheFirstPageOfBothStatusesAtTheDefaultLimit() {
    AttemptQuery query = AttemptQueryParser.parse(null, null, null);

    assertThat(query).isEqualTo(new AttemptQuery(null, null, AttemptQuery.DEFAULT_LIMIT));
  }

  @Test
  void statusCursorAndLimitAreDecodedAsGiven() {
    AttemptCursor cursor =
        new AttemptCursor(
            Instant.parse("2026-10-14T14:00:00Z"),
            UUID.fromString("018f0000-0000-7000-8000-000000000001"));

    AttemptQuery query = AttemptQueryParser.parse("RETRACTED", cursor.encode(), "100");

    assertThat(query).isEqualTo(new AttemptQuery(AttemptStatus.RETRACTED, cursor, 100));
  }

  @Test
  void badStatusAndOutOfRangeLimitAreOneValidationFailure() {
    assertThatThrownBy(() -> AttemptQueryParser.parse("DELETED", null, "0"))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
              assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
              assertThat(e.fieldErrors())
                  .extracting(FieldError::field, FieldError::code)
                  .containsExactly(Tuple.tuple("status", "enum"), Tuple.tuple("limit", "minimum"));
            });
    assertThatThrownBy(() -> AttemptQueryParser.parse(null, null, "101"))
        .isInstanceOfSatisfying(
            ApiException.class,
            e ->
                assertThat(e.fieldErrors())
                    .containsExactly(new FieldError("limit", "maximum", "must be <= 100")));
    assertThatThrownBy(() -> AttemptQueryParser.parse(null, null, "ten"))
        .isInstanceOfSatisfying(
            ApiException.class,
            e ->
                assertThat(e.fieldErrors())
                    .containsExactly(new FieldError("limit", "type", "must be an integer")));
  }

  @Test
  void undecodableCursorIsInvalidCursor() {
    assertThatThrownBy(() -> AttemptQueryParser.parse(null, "not-a-cursor", null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.INVALID_CURSOR);
              assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
              assertThat(e.fieldErrors()).isEmpty();
            });
  }
}
