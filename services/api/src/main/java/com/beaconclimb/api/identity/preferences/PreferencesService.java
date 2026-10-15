package com.beaconclimb.api.identity.preferences;

import com.beaconclimb.api.identity.preferences.PreferencesValidator.Validated;
import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.FieldError;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * {@code putPreferences} (BC-0108). Validation order and codes follow the 422 description of the
 * operation in contracts/openapi.yaml 1.1.0: shape violations are 422 VALIDATION_FAILED with one
 * field error each; a shape-valid body whose board is unknown is 422 UNKNOWN_BOARD; a known board
 * that is not set to that angle is 422 UNSUPPORTED_ANGLE. The last two also populate {@code
 * field_errors} so clients that only map field errors still show the right field.
 *
 * <p>"Invalidate incompatible sessions" from the backlog objective is deferred: recommendation
 * sessions do not exist until BC-0111, so there is nothing to invalidate yet.
 */
@Service
public class PreferencesService {

  private static final Logger log = LoggerFactory.getLogger(PreferencesService.class);

  private final PreferencesRepository repository;
  private final Clock clock;

  public PreferencesService(PreferencesRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  public Optional<PreferencesView> find(UUID userId) {
    return repository.find(userId);
  }

  public PreferencesView put(UUID userId, PreferencesRequest request) {
    List<FieldError> errors = PreferencesValidator.validate(request);
    if (!errors.isEmpty()) {
      throw ApiException.validationFailed(errors);
    }
    Validated valid = PreferencesValidator.toValidated(request);
    List<Integer> angles =
        repository
            .availableAngles(valid.boardId())
            .orElseThrow(
                () ->
                    new ApiException(
                        ErrorCode.UNKNOWN_BOARD,
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "Unknown board",
                        List.of(new FieldError("board_id", "unknown", "no such board"))));
    if (!angles.contains(valid.angleDeg())) {
      String allowed = angles.stream().map(String::valueOf).collect(Collectors.joining(", "));
      throw new ApiException(
          ErrorCode.UNSUPPORTED_ANGLE,
          HttpStatus.UNPROCESSABLE_CONTENT,
          "Angle is not available on this board",
          List.of(new FieldError("angle_deg", "enum", "must be one of " + allowed)));
    }
    PreferencesView view =
        new PreferencesView(
            valid.boardId(), valid.angleDeg(), valid.initialGradeBand(), valid.goals());
    repository.upsert(userId, view, clock.instant());
    log.info("preferences outcome=OK user_id={}", userId);
    return view;
  }
}
