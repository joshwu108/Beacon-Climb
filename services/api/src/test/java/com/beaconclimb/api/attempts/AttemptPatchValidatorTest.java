package com.beaconclimb.api.attempts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beaconclimb.api.platform.json.Jcs;
import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.FieldError;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

/** {@link AttemptPatchValidator} against the AttemptPatch schema and the retraction rule. */
class AttemptPatchValidatorTest {

  @Test
  void correctionKeepsAbsentMembersNullAndRoundsTheFractionToThreeDecimals() {
    AttemptPatch patch =
        AttemptPatchValidator.validate(
            Jcs.parse("{\"sent\":false,\"notes\":\"felt strong\",\"completion_fraction\":0.6667}"));

    assertThat(patch).isEqualTo(new AttemptPatch(null, false, 0.667, null, "felt strong", false));
  }

  @Test
  void retractionAloneIsValid() {
    AttemptPatch patch = AttemptPatchValidator.validate(Jcs.parse("{\"retract\":true}"));

    assertThat(patch).isEqualTo(new AttemptPatch(null, null, null, null, null, true));
  }

  @Test
  void emptyBodyFalseRetractAndRetractWithACorrectionAreValidationFailures() {
    assertValidationFailed("{}", Tuple.tuple("body", "minProperties"));
    assertValidationFailed("{\"retract\":false}", Tuple.tuple("retract", "const"));
    assertValidationFailed("{\"retract\":\"yes\"}", Tuple.tuple("retract", "type"));
    assertValidationFailed(
        "{\"retract\":true,\"sent\":false}", Tuple.tuple("retract", "exclusive"));
  }

  @Test
  void memberRangeViolationsAreCollectedAndUnknownMembersAreUnknownField() {
    assertValidationFailed(
        "{\"attempts_count\":0,\"perceived_effort\":11,\"completion_fraction\":1.5}",
        Tuple.tuple("attempts_count", "minimum"),
        Tuple.tuple("completion_fraction", "maximum"),
        Tuple.tuple("perceived_effort", "maximum"));

    assertThatThrownBy(() -> AttemptPatchValidator.validate(Jcs.parse("{\"attempt_id\":\"x\"}")))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.UNKNOWN_FIELD);
              assertThat(e.fieldErrors())
                  .containsExactly(
                      new FieldError("attempt_id", "additionalProperties", "unknown field"));
            });
  }

  private static void assertValidationFailed(String body, Tuple... fieldAndCode) {
    assertThatThrownBy(() -> AttemptPatchValidator.validate(Jcs.parse(body)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
              assertThat(e.fieldErrors())
                  .extracting(FieldError::field, FieldError::code)
                  .containsExactly(fieldAndCode);
            });
  }
}
