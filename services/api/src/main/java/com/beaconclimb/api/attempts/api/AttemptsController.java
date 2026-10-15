package com.beaconclimb.api.attempts.api;

import com.beaconclimb.api.attempts.AttemptService;
import com.beaconclimb.api.attempts.AttemptService.CreateResult;
import com.beaconclimb.api.identity.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code createAttempt}, {@code listAttempts}, {@code getAttempt} and {@code patchAttempt}
 * (BC-0109). The user comes from the bearer token only. Headers and query parameters arrive raw so
 * a missing or malformed value is the contract's 422 with a field error rather than a framework
 * status; the body is read as text (see {@link RawJsonBody}).
 */
@RestController
public class AttemptsController {

  private final AttemptService attempts;

  public AttemptsController(AttemptService attempts) {
    this.attempts = attempts;
  }

  @PostMapping(
      path = "/v1/me/attempts",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AttemptView> create(
      AuthenticatedUser user,
      @RequestHeader(name = AttemptService.IDEMPOTENCY_KEY_HEADER, required = false)
          String idempotencyKey,
      HttpServletRequest request)
      throws IOException {
    CreateResult result = attempts.create(user.userId(), idempotencyKey, RawJsonBody.read(request));
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(result.view());
  }

  @GetMapping(path = "/v1/me/attempts", produces = MediaType.APPLICATION_JSON_VALUE)
  public AttemptListView list(
      AuthenticatedUser user,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit) {
    return attempts.list(user.userId(), AttemptQueryParser.parse(status, cursor, limit));
  }

  @GetMapping(path = "/v1/me/attempts/{attempt_id}", produces = MediaType.APPLICATION_JSON_VALUE)
  public AttemptView get(AuthenticatedUser user, @PathVariable("attempt_id") String attemptId) {
    return attempts.get(user.userId(), attemptId);
  }

  @PatchMapping(
      path = "/v1/me/attempts/{attempt_id}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public AttemptView patch(
      AuthenticatedUser user,
      @PathVariable("attempt_id") String attemptId,
      @RequestHeader(name = AttemptService.IF_MATCH_HEADER, required = false) String ifMatch,
      HttpServletRequest request)
      throws IOException {
    return attempts.patch(user.userId(), attemptId, ifMatch, RawJsonBody.read(request));
  }
}
