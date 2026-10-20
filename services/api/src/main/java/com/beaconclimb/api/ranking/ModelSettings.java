package com.beaconclimb.api.ranking;

/**
 * The {@code model} block of a model-aware ranking policy (heuristic-v2, BC-0210). Absent (null on
 * {@link Policy#model()}) for heuristic-v1, which never reads model outputs.
 *
 * <ul>
 *   <li>{@code gradeModelVersion}: the registered Model A version whose precomputed predicted
 *       difficulty ({@code route_model_outputs}) blends into the grade the challenge fit scores.
 *   <li>{@code embeddingModelVersion}: the registered version whose 32-d route embeddings drive
 *       embedding novelty and "more like this".
 *   <li>{@code blendK}: prior strength of the blend {@code grade_used = (n * community + k *
 *       predicted) / (n + k)} with {@code n} the ascent count; owner decision 2026-10-19: {@code k
 *       = min_obs}. An untuned default, not a measured optimum.
 *   <li>{@code noveltyDistanceScale}: cosine distance at or beyond which a route counts as fully
 *       novel relative to the user's recent routes ({@code novelty = min(1, d / scale)}). Untuned.
 * </ul>
 */
public record ModelSettings(
    String gradeModelVersion,
    String embeddingModelVersion,
    double blendK,
    double noveltyDistanceScale) {

  public ModelSettings {
    if (blendK <= 0 || noveltyDistanceScale <= 0 || noveltyDistanceScale > 2) {
      throw new IllegalArgumentException(
          "model.blend_k must be positive and model.novelty_distance_scale in (0, 2]");
    }
  }
}
