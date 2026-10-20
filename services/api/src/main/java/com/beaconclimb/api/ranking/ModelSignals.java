package com.beaconclimb.api.ranking;

import java.util.List;
import java.util.Map;

/**
 * Precomputed Model A outputs a model-aware policy reads (BC-0210): per candidate public id the
 * predicted difficulty and the route embedding, plus the embeddings of the user's recent routes in
 * {@code recent_variant_ids} order (routes without an embedding are skipped). All values are read
 * from {@code route_model_outputs} and frozen into the candidate snapshot, so a replay never needs
 * the model. {@link #EMPTY} means "no outputs": heuristic-v2 then scores exactly like v1 except for
 * its own tie-break salt.
 */
public record ModelSignals(
    Map<String, Double> predictedGrade,
    Map<String, float[]> embedding,
    List<float[]> recentEmbeddings) {

  public static final ModelSignals EMPTY = new ModelSignals(Map.of(), Map.of(), List.of());

  public ModelSignals {
    predictedGrade = Map.copyOf(predictedGrade);
    embedding = Map.copyOf(embedding);
    recentEmbeddings = List.copyOf(recentEmbeddings);
  }
}
