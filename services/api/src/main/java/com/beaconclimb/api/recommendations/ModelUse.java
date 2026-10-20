package com.beaconclimb.api.recommendations;

import com.beaconclimb.api.modeloutputs.ModelOutputRepository;
import com.beaconclimb.api.modeloutputs.ModelOutputRepository.ModelVersion;
import com.beaconclimb.api.ranking.ModelSettings;
import com.beaconclimb.api.ranking.ModelSignals;
import com.beaconclimb.api.ranking.Policy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The model outputs one recommendation read (BC-0210) and the registered versions they came from.
 * For a policy without a {@code model} block, or a model version that is not registered READY, the
 * signals are empty and nothing is named: the decision then records {@code model_version "none"} as
 * before.
 */
record ModelUse(ModelSignals signals, ModelVersion grade, ModelVersion embedding) {

  static final ModelUse NONE = new ModelUse(ModelSignals.EMPTY, null, null);

  /** {@code role} as in decision-record v1.1 {@code model_artifacts[].role}. */
  record Artifact(String role, String modelVersion, String artifactDigest) {}

  static ModelUse load(
      Policy policy,
      String boardId,
      int angleDeg,
      List<String> recentVariantIds,
      ModelOutputRepository outputs) {
    if (!policy.usesModel()) {
      return NONE;
    }
    ModelSettings settings = policy.model();
    ModelVersion grade = outputs.readyVersion(settings.gradeModelVersion()).orElse(null);
    ModelVersion embedding = outputs.readyVersion(settings.embeddingModelVersion()).orElse(null);
    Map<String, Double> predicted =
        grade == null ? Map.of() : outputs.predictedGrades(grade.modelVersion(), boardId, angleDeg);
    Map<String, float[]> vectors =
        embedding == null
            ? Map.of()
            : outputs.embeddings(embedding.modelVersion(), boardId, angleDeg);
    List<float[]> recent =
        embedding == null || vectors.isEmpty()
            ? List.of()
            : outputs.embeddingsOf(embedding.modelVersion(), recentVariantIds);
    return new ModelUse(
        new ModelSignals(predicted, vectors, recent),
        predicted.isEmpty() ? null : grade,
        vectors.isEmpty() ? null : embedding);
  }

  /** The grade model's version, or {@code "none"} when no prediction was read. */
  String modelVersion() {
    return grade == null ? DecisionRecordJson.MODEL_VERSION_NONE : grade.modelVersion();
  }

  String modelArtifactDigest() {
    return grade == null ? null : grade.artifactDigest();
  }

  List<Artifact> artifacts() {
    List<Artifact> out = new ArrayList<>();
    if (grade != null) {
      out.add(new Artifact("grade", grade.modelVersion(), grade.artifactDigest()));
    }
    if (embedding != null) {
      out.add(new Artifact("embedding", embedding.modelVersion(), embedding.artifactDigest()));
    }
    return out;
  }
}
