package com.beaconclimb.api.ranking;

import com.beaconclimb.api.identity.preferences.Goal;
import com.beaconclimb.api.identity.preferences.GradeBand;
import com.beaconclimb.api.platform.json.Jcs;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * A ranking policy (planning/06 section 9): the weights and constraints of one {@code
 * policy_version}, loaded from {@code policies/<version>.json} by {@link #fromJson} and stored
 * canonically in {@code ranking_policies.serialized_config}. {@code codeDigest} is not read from
 * the file; {@link PolicyLoader} computes it over the compiled ranking classes.
 *
 * <p>{@code bandTargets} and {@code goalOffsets} are planning placeholders mapping a self-assessed
 * band and a session goal to a target {@code grade_numeric}; they claim nothing about physical
 * difficulty. {@code target_grade = band_targets[band] + goal_offsets[goal]} (one double addition).
 */
public record Policy(
    String policyVersion,
    Weights weights,
    double gradeWindow,
    int minObs,
    int cooldownHours,
    Map<GradeBand, Double> bandTargets,
    Map<Goal, Double> goalOffsets,
    Map<Goal, List<String>> preferredTags,
    int lowInventoryMin,
    String codeDigest) {

  /** The five PRD 7.4 weights; {@code skillGap} multiplies a constant 0 in v1. */
  public record Weights(
      double challenge, double skillGap, double novelty, double preference, double confidence) {}

  public Policy {
    bandTargets = Collections.unmodifiableMap(new EnumMap<>(bandTargets));
    goalOffsets = Collections.unmodifiableMap(new EnumMap<>(goalOffsets));
    preferredTags = Collections.unmodifiableMap(copyTags(preferredTags));
    for (GradeBand band : GradeBand.values()) {
      if (!bandTargets.containsKey(band)) {
        throw new IllegalArgumentException("band_targets misses " + band);
      }
    }
    for (Goal goal : Goal.values()) {
      if (!goalOffsets.containsKey(goal)) {
        throw new IllegalArgumentException("goal_offsets misses " + goal);
      }
    }
    if (gradeWindow <= 0 || minObs <= 0 || cooldownHours < 0 || lowInventoryMin < 0) {
      throw new IllegalArgumentException(
          "grade_window and min_obs must be positive, hours non-negative");
    }
  }

  public Policy withCodeDigest(String digest) {
    return new Policy(
        policyVersion,
        weights,
        gradeWindow,
        minObs,
        cooldownHours,
        bandTargets,
        goalOffsets,
        preferredTags,
        lowInventoryMin,
        digest);
  }

  public double targetGrade(GradeBand band, Goal goal) {
    return bandTargets.get(band) + goalOffsets.get(goal);
  }

  public List<String> preferredTagsFor(Goal goal) {
    return preferredTags.getOrDefault(goal, List.of());
  }

  /** Parses the policy resource strictly (duplicate keys rejected); unknown members are ignored. */
  public static Policy fromJson(String json) {
    JsonNode node = Jcs.parse(json);
    JsonNode w = required(node, "weights");
    Weights weights =
        new Weights(
            required(w, "challenge").doubleValue(),
            required(w, "skill_gap").doubleValue(),
            required(w, "novelty").doubleValue(),
            required(w, "preference").doubleValue(),
            required(w, "confidence").doubleValue());
    Map<GradeBand, Double> bands = new EnumMap<>(GradeBand.class);
    for (GradeBand band : GradeBand.values()) {
      bands.put(band, required(required(node, "band_targets"), band.name()).doubleValue());
    }
    Map<Goal, Double> offsets = new EnumMap<>(Goal.class);
    Map<Goal, List<String>> tags = new EnumMap<>(Goal.class);
    JsonNode preferred = required(node, "preferred_tags");
    for (Goal goal : Goal.values()) {
      offsets.put(goal, required(required(node, "goal_offsets"), goal.name()).doubleValue());
      List<String> goalTags = new ArrayList<>();
      JsonNode list = preferred.get(goal.name());
      if (list != null) {
        list.forEach(tag -> goalTags.add(tag.asString()));
      }
      tags.put(goal, goalTags);
    }
    return new Policy(
        required(node, "policy_version").asString(),
        weights,
        required(node, "grade_window").doubleValue(),
        required(node, "min_obs").asInt(),
        required(node, "cooldown_hours").asInt(),
        bands,
        offsets,
        tags,
        required(node, "low_inventory_min").asInt(),
        null);
  }

  private static JsonNode required(JsonNode node, String key) {
    JsonNode value = node.get(key);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException("policy misses " + key);
    }
    return value;
  }

  private static Map<Goal, List<String>> copyTags(Map<Goal, List<String>> tags) {
    Map<Goal, List<String>> copy = new EnumMap<>(Goal.class);
    tags.forEach((goal, list) -> copy.put(goal, List.copyOf(list)));
    return copy;
  }
}
