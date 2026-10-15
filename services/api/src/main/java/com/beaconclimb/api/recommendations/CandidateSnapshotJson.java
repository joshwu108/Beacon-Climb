package com.beaconclimb.api.recommendations;

import com.beaconclimb.api.features.FeatureAggregates;
import com.beaconclimb.api.identity.preferences.Goal;
import com.beaconclimb.api.platform.web.Timestamps;
import com.beaconclimb.api.ranking.Candidate;
import com.beaconclimb.api.ranking.FilterOutcome;
import com.beaconclimb.api.ranking.Policy;
import com.beaconclimb.api.ranking.RankResult;
import com.beaconclimb.api.ranking.ScoreInputs;
import com.beaconclimb.api.ranking.Scoring;
import com.beaconclimb.api.ranking.UserContext;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the {@code CandidateSnapshot} document of contracts/decisions/decision-record.v1 (ADR-0003
 * "Snapshot contains ..."): the frozen user features plus the policy-derived target grade, and
 * every candidate with the exact scoring inputs the ranker read ({@link Scoring#inputs}), its
 * hard-filter outcomes and the (absent) model outputs. Ineligible candidates are listed for audit
 * with {@code eligible=false}. The five filter results are evaluated independently here and
 * cross-checked against the ranker's verdict: a disagreement is a bug and throws, except that a
 * low-inventory widening legitimately makes a cooled candidate eligible with {@code cooldown=false}
 * (its served positions carry LOW_INVENTORY_WIDENED). No notes, credentials or raw attempt history
 * appear.
 */
final class CandidateSnapshotJson {

  static final String FEATURE_SCHEMA_VERSION = "climber-features.v1";
  static final String TARGET_GRADE_KEY = "target_grade_numeric";

  /** Everything the snapshot is built from. */
  record Inputs(
      UUID userId,
      Instant featureAsOf,
      String catalogVersion,
      FeatureAggregates aggregates,
      List<Candidate> candidates,
      Map<String, Integer> holdCounts,
      UserContext context,
      Policy policy,
      RankResult result) {}

  private CandidateSnapshotJson() {}

  static JsonNode build(Inputs in) {
    ObjectNode root = JsonNodeFactory.instance.objectNode();
    root.put("artifact_type", "candidate_snapshot");
    root.put("schema_version", 1);
    root.put("user_id", in.userId().toString());
    root.put("feature_as_of", Timestamps.format(in.featureAsOf()));
    root.put("feature_schema_version", FEATURE_SCHEMA_VERSION);
    root.put("catalog_version", in.catalogVersion());
    ObjectNode features = (ObjectNode) in.aggregates().toJsonNode();
    features.put(
        TARGET_GRADE_KEY, in.policy().targetGrade(in.context().gradeBand(), in.context().goal()));
    root.set("user_features", features);
    Set<String> rejected = new HashSet<>();
    for (FilterOutcome outcome : in.result().rejected()) {
      rejected.add(outcome.routeVariantId());
    }
    ArrayNode candidates = root.putArray("candidates");
    for (Candidate candidate : in.candidates()) {
      candidates.add(candidate(candidate, in, !rejected.contains(candidate.routeVariantId())));
    }
    return root;
  }

  private static ObjectNode candidate(Candidate candidate, Inputs in, boolean eligible) {
    UserContext context = in.context();
    Policy policy = in.policy();
    boolean cooldownActive = cooldownActive(candidate, context.now(), policy);
    boolean board = candidate.boardId().equals(context.boardId());
    boolean angle = candidate.angleDeg() == context.angleDeg();
    boolean active = Candidate.ACTIVE.equals(candidate.validationStatus());
    boolean notExcluded = !context.excludeIds().contains(candidate.routeVariantId());
    boolean cooldownPassed = context.goal() == Goal.PROJECT || !cooldownActive;
    verifyAgainstRanker(
        candidate,
        eligible,
        board && angle && active && notExcluded,
        cooldownPassed,
        in.result().lowInventoryWidened());
    ScoreInputs inputs = Scoring.inputs(candidate, context, policy);

    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("route_variant_id", candidate.routeVariantId());
    node.put("eligible", eligible);
    ObjectNode filters = node.putObject("filter_results");
    filters.put("board", board);
    filters.put("angle", angle);
    filters.put("active", active);
    filters.put("exclude", notExcluded);
    filters.put("cooldown", cooldownPassed);
    ObjectNode features = node.putObject("features");
    features.put("grade_numeric", candidate.gradeNumeric());
    features.put("hold_count", in.holdCounts().get(candidate.routeVariantId()));
    features.put("novelty", inputs.novelty());
    features.put("cooldown_active", cooldownActive);
    features.put("preference_match", inputs.preferenceMatch());
    features.put("grade_observation_count", candidate.gradeObservationCount());
    ObjectNode outputs = node.putObject("model_outputs");
    outputs.putNull("predicted_grade");
    outputs.putNull("embedding_distance");
    outputs.putNull("predicted_send_probability");
    return node;
  }

  /** Mirrors {@code CandidateFilter.inCooldown}: last attempt strictly after now - cooldown. */
  static boolean cooldownActive(Candidate candidate, Instant now, Policy policy) {
    Instant last = candidate.lastAttemptedAt();
    return last != null && last.isAfter(now.minus(Duration.ofHours(policy.cooldownHours())));
  }

  private static void verifyAgainstRanker(
      Candidate candidate,
      boolean eligible,
      boolean othersPass,
      boolean cooldownPassed,
      boolean widened) {
    boolean expected = othersPass && (cooldownPassed || (widened && eligible));
    if (eligible != expected) {
      throw new IllegalStateException(
          "snapshot filter results disagree with the ranker for " + candidate.routeVariantId());
    }
  }
}
