package com.beaconclimb.api.attempts;

import com.beaconclimb.api.attempts.api.AttemptListView;
import com.beaconclimb.api.attempts.api.AttemptView;
import com.beaconclimb.api.catalog.CatalogQueryRepository;
import com.beaconclimb.api.catalog.CatalogQueryRepository.VariantRow;
import com.beaconclimb.api.catalog.CatalogService;
import com.beaconclimb.api.catalog.RouteVariantId;
import com.beaconclimb.api.identity.ConsentGuard;
import com.beaconclimb.api.identity.Uuid7;
import com.beaconclimb.api.platform.json.Jcs;
import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.FieldError;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Attempt logging (BC-0109, ADR-0002). Order of checks on create: Idempotency-Key and body shape
 * (422), the five-minute future skew (422 OCCURRED_AT_IN_FUTURE), consent (403), an existing key
 * (200 replay or 409 IDEMPOTENCY_CONFLICT), the visible route variant (422 UNKNOWN_ROUTE_VARIANT),
 * then one transaction that inserts the attempt with {@code ON CONFLICT DO NOTHING}, its revision 1
 * snapshot and the ATTEMPT_RECORDED outbox event. The fingerprint is the SHA-256 of the RFC 8785
 * canonical form of the raw request body (BC-0109-JCS). Timestamps are truncated to milliseconds at
 * ingest so stored, replayed and re-read bodies agree.
 *
 * <p>PATCH applies ownership first (404, TM-02), then the If-Match header (422), then refuses any
 * change to a RETRACTED attempt (422, field {@code status}), then the revision predicate in the
 * UPDATE itself (409 REVISION_CONFLICT with {@code current_revision}); an accepted change writes
 * the next snapshot and its ATTEMPT_CORRECTED or ATTEMPT_RETRACTED event in the same transaction.
 * Log lines carry ids and outcomes only, never notes or bodies (TM-10, TM-16).
 */
@Service
public class AttemptService {

  /** Outcome of {@code createAttempt}: 201 for a new row, 200 for an idempotent replay. */
  public record CreateResult(AttemptView view, boolean created) {}

  static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);
  public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
  public static final String IF_MATCH_HEADER = "If-Match";
  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
  private static final Pattern IF_MATCH = Pattern.compile("^\"([1-9][0-9]{0,17})\"$");
  private static final Pattern UUID_CANONICAL =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Logger log = LoggerFactory.getLogger(AttemptService.class);

  private final AttemptRepository attempts;
  private final AttemptAuditRepository audit;
  private final AttemptEvents events;
  private final CatalogQueryRepository catalog;
  private final ConsentGuard consent;
  private final TransactionTemplate tx;
  private final JsonMapper json;
  private final Clock clock;

  public AttemptService(
      AttemptRepository attempts,
      AttemptAuditRepository audit,
      AttemptEvents events,
      CatalogQueryRepository catalog,
      ConsentGuard consent,
      TransactionTemplate tx,
      JsonMapper json,
      Clock clock) {
    this.attempts = attempts;
    this.audit = audit;
    this.events = events;
    this.catalog = catalog;
    this.consent = consent;
    this.tx = tx;
    this.json = json;
    this.clock = clock;
  }

  public CreateResult create(UUID userId, String idempotencyKey, String rawBody) {
    validateIdempotencyKey(idempotencyKey);
    JsonNode body = AttemptFields.parseObject(rawBody);
    String fingerprint = Jcs.sha256Hex(Jcs.canonicalize(body).getBytes(StandardCharsets.UTF_8));
    AttemptCreate create = AttemptCreateValidator.validate(body);
    Instant now = now();
    if (create.occurredAt().isAfter(now.plus(MAX_FUTURE_SKEW))) {
      throw occurredAtInFuture();
    }
    consent.requireActiveConsent(userId);
    Optional<AttemptRow> existing = attempts.findByKey(userId, idempotencyKey);
    if (existing.isPresent()) {
      return replay(existing.get(), fingerprint);
    }
    VariantRow variant =
        catalog
            .findVisibleVariant(create.routeVariantId(), now)
            .orElseThrow(AttemptService::unknownRouteVariant);
    AttemptRow row = newRow(userId, create, variant, now);
    String traceId = TraceIds.current();
    boolean inserted =
        Boolean.TRUE.equals(
            tx.execute(status -> record(row, idempotencyKey, fingerprint, traceId)));
    if (inserted) {
      log.info("attempt outcome=CREATED user_id={} attempt_id={}", userId, row.attemptId());
      return new CreateResult(AttemptView.of(AttemptBody.of(row), row.route()), true);
    }
    AttemptRow committed =
        attempts
            .findByKey(userId, idempotencyKey)
            .orElseThrow(() -> new IllegalStateException("idempotency conflict without a row"));
    return replay(committed, fingerprint);
  }

  public AttemptView get(UUID userId, String attemptId) {
    AttemptRow row =
        attempts.findOwned(userId, parseAttemptId(attemptId)).orElseThrow(AttemptService::notFound);
    return AttemptView.of(AttemptBody.of(row), row.route());
  }

  public AttemptListView list(UUID userId, AttemptQuery query) {
    List<AttemptRow> rows = attempts.list(userId, query);
    boolean hasMore = rows.size() > query.limit();
    List<AttemptRow> page = hasMore ? rows.subList(0, query.limit()) : rows;
    AttemptRow last = page.isEmpty() ? null : page.get(page.size() - 1);
    String next = hasMore ? new AttemptCursor(last.occurredAt(), last.attemptId()).encode() : null;
    return new AttemptListView(
        page.stream().map(row -> AttemptView.of(AttemptBody.of(row), row.route())).toList(), next);
  }

  public AttemptView patch(UUID userId, String attemptId, String ifMatch, String rawBody) {
    UUID id = parseAttemptId(attemptId);
    AttemptPatch patch = AttemptPatchValidator.validate(AttemptFields.parseObject(rawBody));
    String traceId = TraceIds.current();
    return tx.execute(status -> applyPatch(userId, id, ifMatch, patch, traceId));
  }

  /** Inside the create transaction: the insert and, only when it won, the audit row and event. */
  private boolean record(
      AttemptRow row, String idempotencyKey, String fingerprint, String traceId) {
    String bodyJson = json.writeValueAsString(AttemptBody.of(row));
    if (!attempts.insertIfAbsent(row, idempotencyKey, fingerprint, bodyJson)) {
      return false;
    }
    appendAudit(row, AttemptChange.RECORDED, bodyJson, row.receivedAt(), traceId);
    return true;
  }

  private AttemptView applyPatch(
      UUID userId, UUID id, String ifMatch, AttemptPatch patch, String traceId) {
    AttemptRow current = attempts.findOwned(userId, id).orElseThrow(AttemptService::notFound);
    int expectedRevision = parseIfMatch(ifMatch);
    if (current.status() == AttemptStatus.RETRACTED) {
      throw ApiException.validationFailed(
          List.of(new FieldError("status", "retracted", "Retracted attempts cannot be changed")));
    }
    Instant now = now();
    AttemptRow next = merge(current, patch, expectedRevision + 1);
    if (attempts.updateIfRevision(next, expectedRevision, now) == 0) {
      int currentRevision =
          attempts
              .findOwned(userId, id)
              .map(AttemptRow::revision)
              .orElseThrow(AttemptService::notFound);
      log.info(
          "attempt outcome=REVISION_CONFLICT user_id={} attempt_id={} current_revision={}",
          userId,
          id,
          currentRevision);
      throw ApiException.revisionConflict(currentRevision);
    }
    AttemptChange change = patch.retract() ? AttemptChange.RETRACTED : AttemptChange.CORRECTED;
    appendAudit(next, change, json.writeValueAsString(AttemptBody.of(next)), now, traceId);
    log.info(
        "attempt outcome={} user_id={} attempt_id={} revision={}",
        change,
        userId,
        id,
        next.revision());
    return AttemptView.of(AttemptBody.of(next), next.route());
  }

  private void appendAudit(
      AttemptRow row, AttemptChange change, String snapshotJson, Instant at, String traceId) {
    audit.insertRevision(row.attemptId(), row.revision(), snapshotJson, at, change);
    UUID eventId = Uuid7.next(clock);
    String payload = events.payload(change, eventId, row, at, traceId);
    audit.insertEvent(eventId, row.attemptId(), row.revision(), change, payload, at);
  }

  /** Same fingerprint: the persisted 201 body with HTTP 200; otherwise 409 (ADR-0002). */
  private CreateResult replay(AttemptRow row, String fingerprint) {
    if (!row.requestFingerprint().equals(fingerprint)) {
      log.info(
          "attempt outcome=IDEMPOTENCY_CONFLICT user_id={} attempt_id={}",
          row.userId(),
          row.attemptId());
      throw new ApiException(
          ErrorCode.IDEMPOTENCY_CONFLICT,
          HttpStatus.CONFLICT,
          "Key was used with a different request");
    }
    AttemptBody stored = json.readValue(row.responseBodyJson(), AttemptBody.class);
    log.info("attempt outcome=REPLAYED user_id={} attempt_id={}", row.userId(), row.attemptId());
    return new CreateResult(AttemptView.of(stored, row.route()), false);
  }

  private AttemptRow newRow(UUID userId, AttemptCreate create, VariantRow variant, Instant now) {
    return new AttemptRow(
        Uuid7.next(clock),
        userId,
        variant.variantId(),
        RouteVariantId.format(variant.providerRouteUuid(), variant.angleDeg()),
        create.occurredAt(),
        now,
        create.attemptsCount(),
        create.sent(),
        create.completionFraction(),
        create.perceivedEffort(),
        create.notes(),
        1,
        AttemptStatus.ACTIVE,
        null,
        null,
        CatalogService.summary(variant));
  }

  private static AttemptRow merge(AttemptRow current, AttemptPatch patch, int revision) {
    return new AttemptRow(
        current.attemptId(),
        current.userId(),
        current.variantId(),
        current.routeVariantId(),
        current.occurredAt(),
        current.receivedAt(),
        patch.attemptsCount() != null ? patch.attemptsCount() : current.attemptsCount(),
        patch.sent() != null ? patch.sent() : current.sent(),
        patch.completionFraction() != null
            ? patch.completionFraction()
            : current.completionFraction(),
        patch.perceivedEffort() != null ? patch.perceivedEffort() : current.perceivedEffort(),
        patch.notes() != null ? patch.notes() : current.notes(),
        revision,
        patch.retract() ? AttemptStatus.RETRACTED : current.status(),
        current.requestFingerprint(),
        current.responseBodyJson(),
        current.route());
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MILLIS);
  }

  static void validateIdempotencyKey(String key) {
    if (key == null) {
      throw ApiException.validationFailed(
          List.of(new FieldError(IDEMPOTENCY_KEY_HEADER, "required", "header is required")));
    }
    if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
      throw ApiException.validationFailed(
          List.of(
              new FieldError(
                  IDEMPOTENCY_KEY_HEADER, "pattern", "must match " + IDEMPOTENCY_KEY.pattern())));
    }
  }

  /** The quoted decimal revision; a value past the int range can never match (409 follows). */
  static int parseIfMatch(String ifMatch) {
    if (ifMatch == null) {
      throw ApiException.validationFailed(
          List.of(new FieldError(IF_MATCH_HEADER, "required", "header is required")));
    }
    var matcher = IF_MATCH.matcher(ifMatch);
    if (!matcher.matches()) {
      throw ApiException.validationFailed(
          List.of(new FieldError(IF_MATCH_HEADER, "pattern", "must match ^\"[1-9][0-9]*\"$")));
    }
    long revision = Long.parseLong(matcher.group(1));
    return revision > Integer.MAX_VALUE ? Integer.MIN_VALUE : (int) revision;
  }

  /** Only canonical UUID text addresses an attempt; anything else is simply not found. */
  private static UUID parseAttemptId(String attemptId) {
    if (attemptId == null || !UUID_CANONICAL.matcher(attemptId).matches()) {
      throw notFound();
    }
    return UUID.fromString(attemptId);
  }

  private static ApiException notFound() {
    return new ApiException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "No such attempt");
  }

  private static ApiException unknownRouteVariant() {
    return new ApiException(
        ErrorCode.UNKNOWN_ROUTE_VARIANT,
        HttpStatus.UNPROCESSABLE_CONTENT,
        "Unknown route variant",
        List.of(new FieldError("route_variant_id", "unknown", "no such active route variant")));
  }

  private static ApiException occurredAtInFuture() {
    return new ApiException(
        ErrorCode.OCCURRED_AT_IN_FUTURE,
        HttpStatus.UNPROCESSABLE_CONTENT,
        "occurred_at is more than five minutes after receipt",
        List.of(
            new FieldError(
                "occurred_at", "OCCURRED_AT_IN_FUTURE", "Timestamp is too far in the future")));
  }
}
