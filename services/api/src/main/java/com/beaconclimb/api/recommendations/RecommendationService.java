package com.beaconclimb.api.recommendations;

import com.beaconclimb.api.catalog.CatalogQueryRepository.VariantRow;
import com.beaconclimb.api.catalog.CatalogService;
import com.beaconclimb.api.catalog.RouteVariantId;
import com.beaconclimb.api.catalog.api.RouteVariantSummaryView;
import com.beaconclimb.api.features.FeatureAggregates;
import com.beaconclimb.api.features.FeatureRecomputeService;
import com.beaconclimb.api.features.FeatureSnapshot;
import com.beaconclimb.api.features.FeatureSnapshotRepository;
import com.beaconclimb.api.identity.ConsentGuard;
import com.beaconclimb.api.identity.Uuid7;
import com.beaconclimb.api.identity.preferences.PreferencesService;
import com.beaconclimb.api.identity.preferences.PreferencesView;
import com.beaconclimb.api.platform.json.Jcs;
import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.FieldError;
import com.beaconclimb.api.platform.web.Timestamps;
import com.beaconclimb.api.ranking.Candidate;
import com.beaconclimb.api.ranking.Policy;
import com.beaconclimb.api.ranking.PolicyRegistry;
import com.beaconclimb.api.ranking.PolicyRegistry.StoredPolicy;
import com.beaconclimb.api.ranking.RankResult;
import com.beaconclimb.api.ranking.Ranked;
import com.beaconclimb.api.ranking.Ranker;
import com.beaconclimb.api.ranking.UserContext;
import com.beaconclimb.api.recommendations.CandidateRepository.BoardInfo;
import com.beaconclimb.api.recommendations.CandidateRepository.CandidateRow;
import com.beaconclimb.api.recommendations.DecisionLedgerRepository.LedgerEntry;
import com.beaconclimb.api.recommendations.api.RecommendationResponse;
import com.beaconclimb.api.recommendations.api.RecommendationView;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * {@code createRecommendations} (BC-0111, planning/06 section 10, ADR-0003). Order of checks: body
 * shape (422 VALIDATION_FAILED / UNKNOWN_FIELD), consent (403 CONSENT_REQUIRED), the board (422
 * UNKNOWN_BOARD) and its angle (422 UNSUPPORTED_ANGLE), the user's preferences (422
 * VALIDATION_FAILED, field {@code preferences}: the grade band the policy needs comes from there
 * and nothing else), the active policy (503 when none is registered). Then:
 *
 * <ol>
 *   <li>features: the user's climber-features.v1 snapshot, or a synchronous recompute at {@code W =
 *       now} when none exists. {@code cold_start} is the ranker's verdict, which is true exactly
 *       when the snapshot included no ACTIVE attempt ({@code recent_variant_ids} empty and no
 *       graded send; equivalently {@code feature_as_of} null). {@code feature_as_of} in the
 *       response and the record is the snapshot's value, or {@code cutoff_w} when the history is
 *       empty, so the contract's non-null date-time always states up to when attempts were read.
 *   <li>candidates: one query for the board's visible ACTIVE variants at the angle (see {@link
 *       CandidateRepository} for the snapshot-schema precondition), one for the user's last ACTIVE
 *       attempt per variant (cooldown).
 *   <li>{@link Ranker#rank}; then the candidate snapshot and the decision record are built,
 *       validated against the bundled contract (a violation is a bug and a 500: nothing is
 *       written), the snapshot bytes go to the {@link SnapshotStore} (503 when its quota is
 *       reached), and the decision row with its exposures commits in one transaction. If that
 *       transaction fails, a snapshot file this request created is removed again.
 * </ol>
 *
 * <p>Determinism (TM-09): the ranking depends only on the candidate set, the frozen features, the
 * policy and {@code (user_id, session_id)}; two calls with the same session id and context yield
 * the same ids and scores under new decision ids. Log lines carry ids and counts only.
 */
@Service
public class RecommendationService {

  private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

  private final ConsentGuard consent;
  private final PreferencesService preferences;
  private final PolicyRegistry policies;
  private final FeatureSnapshotRepository snapshots;
  private final FeatureRecomputeService features;
  private final CandidateRepository candidates;
  private final SnapshotStore store;
  private final DecisionRecordSchema schema;
  private final DecisionLedgerRepository ledger;
  private final TransactionTemplate tx;
  private final Clock clock;

  public RecommendationService(
      ConsentGuard consent,
      PreferencesService preferences,
      PolicyRegistry policies,
      FeatureSnapshotRepository snapshots,
      FeatureRecomputeService features,
      CandidateRepository candidates,
      SnapshotStore store,
      DecisionRecordSchema schema,
      DecisionLedgerRepository ledger,
      TransactionTemplate tx,
      Clock clock) {
    this.consent = consent;
    this.preferences = preferences;
    this.policies = policies;
    this.snapshots = snapshots;
    this.features = features;
    this.candidates = candidates;
    this.store = store;
    this.schema = schema;
    this.ledger = ledger;
    this.tx = tx;
    this.clock = clock;
  }

  public RecommendationResponse recommend(UUID userId, RecommendationRequest body) {
    ValidatedRequest request = RecommendationValidator.validate(body);
    consent.requireActiveConsent(userId);
    BoardInfo board = candidates.board(request.boardId()).orElseThrow(this::unknownBoard);
    if (!board.availableAngles().contains(request.angleDeg())) {
      throw unsupportedAngle(board.availableAngles());
    }
    PreferencesView prefs = preferences.find(userId).orElseThrow(this::missingPreferences);
    Policy policy = activePolicy();
    StoredPolicy storedPolicy =
        policies.find(policy.policyVersion()).orElseThrow(this::policyUnavailable);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    FeatureSnapshot snapshot =
        snapshots
            .find(userId, FeatureAggregates.SCHEMA_VERSION)
            .orElseGet(() -> features.recompute(userId, now));
    List<CandidateRow> rows = candidates.candidates(board.boardId(), request.angleDeg(), now);
    Map<String, Instant> lastAttempts =
        candidates.lastAttemptedAt(userId, board.boardId(), request.angleDeg());
    List<Candidate> pool = new ArrayList<>(rows.size());
    Map<String, Integer> holdCounts = new HashMap<>();
    Map<String, VariantRow> variants = new HashMap<>();
    for (CandidateRow row : rows) {
      Candidate candidate = candidate(row, lastAttempts);
      pool.add(candidate);
      holdCounts.put(candidate.routeVariantId(), row.holdCount());
      variants.put(candidate.routeVariantId(), row.variant());
    }
    FeatureAggregates aggregates = snapshot.aggregates();
    UserContext context =
        new UserContext(
            userId,
            request.sessionId(),
            board.boardId(),
            request.angleDeg(),
            request.goal(),
            prefs.initialGradeBand(),
            aggregates.recentVariantIds(),
            Set.copyOf(request.excludeRouteVariantIds()),
            aggregates.maxSentGradeNumeric90d(),
            now);
    RankResult result = Ranker.rank(pool, context, policy, request.maxRoutes());
    Instant featureAsOf =
        snapshot.featureAsOf() != null ? snapshot.featureAsOf() : snapshot.cutoffW();

    JsonNode snapshotJson =
        CandidateSnapshotJson.build(
            new CandidateSnapshotJson.Inputs(
                userId,
                featureAsOf,
                board.catalogVersion(),
                aggregates,
                pool,
                holdCounts,
                context,
                policy,
                result));
    schema.requireValidSnapshot(snapshotJson);
    byte[] snapshotBytes = Jcs.canonicalize(snapshotJson).getBytes(StandardCharsets.UTF_8);
    String digest = Jcs.sha256Hex(snapshotBytes);
    UUID decisionId = Uuid7.next(clock);
    JsonNode record =
        DecisionRecordJson.build(
            new DecisionRecordJson.Inputs(
                decisionId,
                userId,
                now,
                request,
                policy.policyVersion(),
                Jcs.parse(storedPolicy.serializedConfig()),
                policy.codeDigest(),
                snapshot.userId(),
                featureAsOf,
                digest,
                ReplayDigests.rngSeed(policy.policyVersion(), userId, request.sessionId()),
                result.ranked(),
                ReplayDigests.environmentDigest(policy.codeDigest()),
                board.catalogVersion()));
    schema.requireValidRecord(record);
    LedgerEntry entry =
        new LedgerEntry(
            decisionId,
            userId,
            request.sessionId(),
            now,
            policy.policyVersion(),
            policy.codeDigest(),
            DecisionRecordJson.MODEL_VERSION_NONE,
            snapshot.userId(),
            snapshot.featureSchemaVersion(),
            snapshot.featureAsOf(),
            snapshot.cutoffW(),
            digest,
            SnapshotStore.uri(digest),
            record.get("rng_seed").stringValue(),
            record.get("environment_digest").stringValue(),
            Jcs.canonicalize(request.toJsonNode()),
            Jcs.canonicalize(record),
            result.coldStart(),
            false);
    persist(entry, snapshotBytes, digest, result.ranked());
    log.info(
        "recommendation outcome=DECIDED user_id={} decision_id={} candidates={} served={}"
            + " cold_start={}",
        userId,
        decisionId,
        pool.size(),
        result.ranked().size(),
        result.coldStart());
    return response(entry, result.ranked(), variants);
  }

  /**
   * Snapshot file first, then the ledger transaction; a file this request created is rolled back.
   */
  private void persist(
      LedgerEntry entry, byte[] snapshotBytes, String digest, List<Ranked> ranked) {
    boolean existed = store.exists(digest);
    String stored;
    try {
      stored = store.put(snapshotBytes);
    } catch (SnapshotQuotaExceededException e) {
      log.warn("recommendation outcome=SNAPSHOT_QUOTA user_id={}", entry.userId());
      throw new ApiException(
          ErrorCode.SERVICE_UNAVAILABLE,
          HttpStatus.SERVICE_UNAVAILABLE,
          "Recommendations are temporarily unavailable");
    }
    if (!stored.equals(digest)) {
      throw new IllegalStateException("snapshot store returned a different digest");
    }
    try {
      tx.executeWithoutResult(
          status -> {
            ledger.insertDecision(entry);
            ledger.insertExposures(entry.decisionId(), ranked);
          });
    } catch (RuntimeException e) {
      if (!existed) {
        store.delete(digest);
      }
      throw e;
    }
  }

  private static Candidate candidate(CandidateRow row, Map<String, Instant> lastAttempts) {
    VariantRow variant = row.variant();
    String publicId = RouteVariantId.format(variant.providerRouteUuid(), variant.angleDeg());
    return new Candidate(
        publicId,
        variant.boardId(),
        variant.angleDeg(),
        variant.gradeNumeric().doubleValue(),
        variant.gradeObservationCount(),
        Candidate.ACTIVE,
        row.tags(),
        lastAttempts.get(publicId));
  }

  private RecommendationResponse response(
      LedgerEntry entry, List<Ranked> ranked, Map<String, VariantRow> variants) {
    List<RecommendationView> views = new ArrayList<>(ranked.size());
    for (Ranked item : ranked) {
      RouteVariantSummaryView route = CatalogService.summary(variants.get(item.routeVariantId()));
      views.add(
          new RecommendationView(
              item.routeVariantId(),
              route,
              item.position(),
              item.score(),
              item.reasonCodes(),
              null,
              UncertaintyLabel.INSUFFICIENT_HISTORY));
    }
    Instant featureAsOf =
        entry.featureAsOf() != null ? entry.featureAsOf() : entry.featureCutoffW();
    return new RecommendationResponse(
        entry.decisionId(),
        entry.policyVersion(),
        entry.modelVersion(),
        Timestamps.format(featureAsOf),
        entry.coldStart(),
        views,
        entry.fallbackUsed());
  }

  private Policy activePolicy() {
    try {
      return policies.active();
    } catch (IllegalStateException e) {
      throw policyUnavailable();
    }
  }

  private ApiException policyUnavailable() {
    log.warn("recommendation outcome=POLICY_UNAVAILABLE");
    return new ApiException(
        ErrorCode.SERVICE_UNAVAILABLE,
        HttpStatus.SERVICE_UNAVAILABLE,
        "Recommendations are temporarily unavailable");
  }

  private ApiException unknownBoard() {
    return new ApiException(
        ErrorCode.UNKNOWN_BOARD,
        HttpStatus.UNPROCESSABLE_CONTENT,
        "Unknown board",
        List.of(new FieldError("board_id", "unknown", "no such board")));
  }

  private ApiException unsupportedAngle(List<Integer> angles) {
    String allowed = angles.stream().map(String::valueOf).collect(Collectors.joining(", "));
    return new ApiException(
        ErrorCode.UNSUPPORTED_ANGLE,
        HttpStatus.UNPROCESSABLE_CONTENT,
        "Angle is not available on this board",
        List.of(new FieldError("angle_deg", "enum", "must be one of " + allowed)));
  }

  private ApiException missingPreferences() {
    return ApiException.validationFailed(
        List.of(
            new FieldError(
                "preferences", "missing", "Set preferences before requesting recommendations")));
  }
}
