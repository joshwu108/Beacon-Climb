package com.beaconclimb.api.recommendations;

import com.beaconclimb.api.platform.web.Timestamps;
import com.beaconclimb.api.ranking.Ranked;
import com.beaconclimb.api.ranking.ReasonCode;
import com.beaconclimb.api.recommendations.decisions.Explanations;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the {@code FullRecord} document of contracts/decisions/decision-record.v1 (ADR-0003
 * "Record stores ..."). {@code feature_snapshot_id} is the {@code user_feature_snapshots} key the
 * ranking read (the table is keyed by user and schema version, so the user id addresses the row
 * together with {@code feature_schema_version} and the ledger's {@code feature_cutoff_w}). No
 * experiment exists yet, so the assignment members are null. {@code explanation} maps each served
 * route variant id to the text {@link Explanations#render} derives from its reason codes alone.
 */
final class DecisionRecordJson {

  static final String MODEL_VERSION_NONE = "none";

  /** Everything the record is built from. */
  record Inputs(
      UUID decisionId,
      UUID userId,
      Instant requestedAt,
      ValidatedRequest request,
      String policyVersion,
      JsonNode policyConfig,
      String policyCodeDigest,
      UUID featureSnapshotId,
      Instant featureAsOf,
      String candidateSetDigest,
      String rngSeed,
      List<Ranked> ranked,
      String environmentDigest,
      String catalogVersion,
      String modelVersion,
      String modelArtifactDigest,
      List<ModelUse.Artifact> modelArtifacts) {}

  private DecisionRecordJson() {}

  static JsonNode build(Inputs in) {
    ObjectNode root = JsonNodeFactory.instance.objectNode();
    root.put("artifact_type", "decision_record");
    root.put("schema_version", 1);
    root.put("decision_id", in.decisionId().toString());
    root.put("user_id", in.userId().toString());
    root.put("requested_at", Timestamps.format(in.requestedAt()));
    root.put("session_id", in.request().sessionId().toString());
    root.set("request_constraints", in.request().toConstraints());
    root.put("policy_version", in.policyVersion());
    root.set("policy_config", in.policyConfig());
    root.put("policy_code_digest", in.policyCodeDigest());
    root.put("model_version", in.modelVersion());
    if (in.modelArtifactDigest() == null) {
      root.putNull("model_artifact_digest");
    } else {
      root.put("model_artifact_digest", in.modelArtifactDigest());
    }
    if (!in.modelArtifacts().isEmpty()) {
      // decision-record v1.1, optional: written only when model outputs were read, so a
      // heuristic-v1 record stays byte-identical to v1.0.
      ArrayNode artifacts = root.putArray("model_artifacts");
      for (ModelUse.Artifact artifact : in.modelArtifacts()) {
        ObjectNode a = artifacts.addObject();
        a.put("role", artifact.role());
        a.put("model_version", artifact.modelVersion());
        a.put("artifact_digest", artifact.artifactDigest());
      }
    }
    root.put("feature_snapshot_id", in.featureSnapshotId().toString());
    root.put("feature_as_of", Timestamps.format(in.featureAsOf()));
    root.put("feature_schema_version", CandidateSnapshotJson.FEATURE_SCHEMA_VERSION);
    root.put("candidate_set_digest", in.candidateSetDigest());
    root.put("rng_seed", in.rngSeed());
    root.putNull("experiment_id");
    root.putNull("assignment_variant");
    root.putNull("served_variant");
    ArrayNode results = root.putArray("ranked_results");
    ObjectNode explanation = JsonNodeFactory.instance.objectNode();
    for (Ranked item : in.ranked()) {
      results.add(result(item));
      explanation.put(item.routeVariantId(), Explanations.render(item.reasonCodes()));
    }
    root.put("environment_digest", in.environmentDigest());
    root.put("catalog_version", in.catalogVersion());
    root.put("erased", false);
    root.put("candidate_snapshot_uri", SnapshotStore.uri(in.candidateSetDigest()));
    root.set("explanation", explanation);
    return root;
  }

  private static ObjectNode result(Ranked item) {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("route_variant_id", item.routeVariantId());
    node.put("position", item.position());
    node.put("score", item.score());
    ArrayNode codes = node.putArray("reason_codes");
    for (ReasonCode code : item.reasonCodes()) {
      codes.add(code.name());
    }
    return node;
  }
}
