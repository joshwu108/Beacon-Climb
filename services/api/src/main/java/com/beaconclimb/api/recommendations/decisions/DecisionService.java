package com.beaconclimb.api.recommendations.decisions;

import com.beaconclimb.api.catalog.CatalogQueryRepository.VariantRow;
import com.beaconclimb.api.catalog.CatalogService;
import com.beaconclimb.api.catalog.RouteVariantId;
import com.beaconclimb.api.catalog.api.RouteVariantSummaryView;
import com.beaconclimb.api.features.FeatureProvenance;
import com.beaconclimb.api.features.FeatureSource;
import com.beaconclimb.api.platform.json.Jcs;
import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.Timestamps;
import com.beaconclimb.api.ranking.ReasonCode;
import com.beaconclimb.api.recommendations.UncertaintyLabel;
import com.beaconclimb.api.recommendations.api.RecommendationView;
import com.beaconclimb.api.recommendations.decisions.DecisionQueryRepository.ExposureRow;
import com.beaconclimb.api.recommendations.decisions.DecisionQueryRepository.StoredDecision;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * {@code getDecision} (BC-0112). Only canonical UUID text addresses a decision; a malformed id, an
 * unknown id, another user's decision and an erased decision are all the same 404 (TM-01; the
 * contract declares no other error for this operation, and an erased record's existence is not
 * disclosed through the read path). Positions, scores and reason codes come from the exposures
 * written with the decision; the route summaries are the catalog's current rows, so a variant that
 * has since left the catalog is shown with its provider id as name and no grade.
 *
 * <p>{@code features_source} / {@code features_cutoff_w} (contract 1.2.0, BC-0310 part 1) are
 * derived at read time, see {@link #provenance}: the stored decision-record.v1 document ({@code
 * additionalProperties: false}) cannot carry them and no column was added tonight.
 */
@Service
public class DecisionService {

  private static final Logger log = LoggerFactory.getLogger(DecisionService.class);
  private static final Pattern UUID_CANONICAL =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  private final DecisionQueryRepository decisions;

  public DecisionService(DecisionQueryRepository decisions) {
    this.decisions = decisions;
  }

  public DecisionView get(UUID userId, String decisionId) {
    UUID id = parse(decisionId).orElseThrow(DecisionService::notFound);
    StoredDecision decision =
        decisions.findOwned(userId, id).orElseThrow(DecisionService::notFound);
    JsonNode request = Jcs.parse(decision.requestJson());
    JsonNode record = Jcs.parse(decision.recordJson());
    List<ExposureRow> exposures = decisions.exposures(id);
    String boardId = request.get("board_id").stringValue();
    int angle = request.get("angle_deg").asInt();
    Map<String, VariantRow> variants =
        decisions.variants(
            boardId, angle, exposures.stream().map(DecisionService::providerUuid).toList());
    List<RecommendationView> views = new ArrayList<>(exposures.size());
    for (ExposureRow exposure : exposures) {
      views.add(view(exposure, summary(exposure, variants, boardId, angle)));
    }
    FeatureProvenance provenance = provenance(decision);
    log.info("decision outcome=READ user_id={} decision_id={} served={}", userId, id, views.size());
    return new DecisionView(
        decision.decisionId(),
        Timestamps.format(decision.createdAt()),
        request,
        decision.policyVersion(),
        decision.modelVersion(),
        record.get("feature_as_of").stringValue(),
        provenance.source(),
        Timestamps.format(provenance.cutoffW()),
        decision.coldStart(),
        decision.fallbackUsed(),
        views);
  }

  /**
   * Read-time derivation (BC-0310 part 1). Every decision written before the stream read path
   * exists ranked on a SYNC snapshot, so the source is SYNC; the cutoff is the ledger's {@code
   * feature_cutoff_w} column, which BC-0111 has written since V6, so it is omitted only when a row
   * recorded none.
   *
   * <p>TODO(BC-0310 part 2, after BC-0305): persist the source per decision (a column with
   * BC-0305's migration, or decision-record v2 under BC-0401) and read it here instead of assuming
   * SYNC; otherwise a decision ranked on a STREAM row would be reported as SYNC.
   */
  private static FeatureProvenance provenance(StoredDecision decision) {
    return new FeatureProvenance(FeatureSource.SYNC, decision.featureCutoffW());
  }

  private static RecommendationView view(ExposureRow exposure, RouteVariantSummaryView route) {
    return new RecommendationView(
        exposure.routeVariantId(),
        route,
        exposure.position(),
        exposure.rankScore(),
        exposure.reasonCodes().stream().map(ReasonCode::valueOf).toList(),
        null,
        UncertaintyLabel.INSUFFICIENT_HISTORY);
  }

  private static RouteVariantSummaryView summary(
      ExposureRow exposure, Map<String, VariantRow> variants, String boardId, int angle) {
    VariantRow row = variants.get(exposure.routeVariantId());
    if (row != null) {
      return CatalogService.summary(row);
    }
    return new RouteVariantSummaryView(
        exposure.routeVariantId(), boardId, angle, providerUuid(exposure), null, null, 0);
  }

  private static String providerUuid(ExposureRow exposure) {
    return RouteVariantId.parse(exposure.routeVariantId())
        .map(RouteVariantId::providerRouteUuid)
        .orElse(exposure.routeVariantId());
  }

  private static Optional<UUID> parse(String decisionId) {
    if (decisionId == null || !UUID_CANONICAL.matcher(decisionId).matches()) {
      return Optional.empty();
    }
    return Optional.of(UUID.fromString(decisionId));
  }

  private static ApiException notFound() {
    return new ApiException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "No such decision");
  }
}
