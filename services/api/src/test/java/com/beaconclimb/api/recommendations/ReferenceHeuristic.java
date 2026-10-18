package com.beaconclimb.api.recommendations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * An independent restatement of the heuristic-v1 ranking (planning/06 section 9, PRD 7.4, the
 * policy file's own notes and contracts/decisions/examples/heuristic-v1.cjs) written as plain
 * arithmetic over a decision's frozen inputs. It shares nothing with {@code
 * com.beaconclimb.api.ranking}: the policy is read from the record's {@code policy_config}, the tie
 * key is a raw {@link MessageDigest}, and the reason codes are string literals. Used as the oracle
 * of {@link DecisionReplayIT} and the warm-user property of {@link RecommendationPropertyTest}, so
 * a drift between what the ranker did and what was persisted, or between the ranker and the
 * documented formulas, fails a test.
 */
final class ReferenceHeuristic {

  /** The members of {@code policy_config} the ranking reads. */
  record Config(
      String policyVersion,
      double challenge,
      double skillGap,
      double novelty,
      double preference,
      double confidence,
      double gradeWindow,
      int minObs,
      int cooldownHours,
      Map<String, Double> bandTargets,
      Map<String, Double> goalOffsets,
      Map<String, List<String>> preferredTags,
      int lowInventoryMin) {

    static Config fromJson(JsonNode policy) {
      JsonNode weights = policy.get("weights");
      Map<String, Double> bands = new LinkedHashMap<>();
      policy
          .get("band_targets")
          .properties()
          .forEach(e -> bands.put(e.getKey(), e.getValue().doubleValue()));
      Map<String, Double> offsets = new LinkedHashMap<>();
      policy
          .get("goal_offsets")
          .properties()
          .forEach(e -> offsets.put(e.getKey(), e.getValue().doubleValue()));
      Map<String, List<String>> tags = new LinkedHashMap<>();
      policy
          .get("preferred_tags")
          .properties()
          .forEach(
              e -> {
                List<String> list = new ArrayList<>();
                e.getValue().forEach(tag -> list.add(tag.asString()));
                tags.put(e.getKey(), list);
              });
      return new Config(
          policy.get("policy_version").asString(),
          weights.get("challenge").doubleValue(),
          weights.get("skill_gap").doubleValue(),
          weights.get("novelty").doubleValue(),
          weights.get("preference").doubleValue(),
          weights.get("confidence").doubleValue(),
          policy.get("grade_window").doubleValue(),
          policy.get("min_obs").asInt(),
          policy.get("cooldown_hours").asInt(),
          bands,
          offsets,
          tags,
          policy.get("low_inventory_min").asInt());
    }

    /** {@code target_grade = band_targets[band] + goal_offsets[goal]}: one double addition. */
    double targetGrade(String band, String goal) {
      return bandTargets.get(band) + goalOffsets.get(goal);
    }

    List<String> preferredTagsFor(String goal) {
      return preferredTags.getOrDefault(goal, List.of());
    }
  }

  /** One eligible candidate's frozen scoring inputs. */
  record Input(
      String routeVariantId,
      double gradeNumeric,
      int gradeObservationCount,
      double novelty,
      double preferenceMatch) {}

  /** One served position as the record's {@code ranked_results} states it. */
  record Result(String routeVariantId, int position, double score, List<String> reasonCodes) {}

  private record Scored(Input input, double fit, double confidence, double score, String tieKey) {}

  private ReferenceHeuristic() {}

  /** {@code 1 - min(1, |grade - target| / grade_window)}. */
  static double challengeFit(double gradeNumeric, double targetGrade, double gradeWindow) {
    return 1.0 - Math.min(1.0, Math.abs(gradeNumeric - targetGrade) / gradeWindow);
  }

  /** {@code min(1, observations / min_obs)} in double arithmetic. */
  static double confidence(int observations, int minObs) {
    return Math.min(1.0, observations / (double) minObs);
  }

  /** 0 when the variant is among the user's recent variant ids, else 1. */
  static double novelty(List<String> recentVariantIds, String routeVariantId) {
    return recentVariantIds.contains(routeVariantId) ? 0.0 : 1.0;
  }

  /** 1 when any route tag is among the goal's preferred tags, else 0. */
  static double preferenceMatch(List<String> tags, List<String> preferred) {
    for (String tag : tags) {
      if (preferred.contains(tag)) {
        return 1.0;
      }
    }
    return 0.0;
  }

  /**
   * The five products summed strictly left to right: challenge, skill gap (times the constant 0),
   * novelty, preference, confidence. The order is part of the replay contract (ADR-0003).
   */
  static double score(
      Config c, double challengeFit, double novelty, double preferenceMatch, double confidence) {
    double score = c.challenge() * challengeFit;
    score = score + c.skillGap() * 0.0;
    score = score + c.novelty() * novelty;
    score = score + c.preference() * preferenceMatch;
    score = score + c.confidence() * confidence;
    return score;
  }

  /** Lowercase sha256 hex of {@code policy_version|user_id|session_id|route_variant_id}. */
  static String tieKey(String policyVersion, UUID userId, UUID sessionId, String routeVariantId) {
    String text = policyVersion + "|" + userId + "|" + sessionId + "|" + routeVariantId;
    return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
  }

  static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Reason codes in contract order for one served candidate. */
  static List<String> reasonCodes(
      double challengeFit,
      double novelty,
      double preferenceMatch,
      double confidence,
      boolean widened,
      boolean coldStart) {
    List<String> codes = new ArrayList<>();
    codes.add("COMPATIBLE_ANGLE");
    if (!coldStart && challengeFit >= 0.5) {
      codes.add("GOAL_CHALLENGE_MATCH");
    }
    if (novelty == 1.0) {
      codes.add("NOVEL_ROUTE");
    }
    if (preferenceMatch == 1.0) {
      codes.add("PREFERRED_STYLE");
    }
    if (confidence == 1.0) {
      codes.add("WELL_OBSERVED_GRADE");
    }
    if (widened) {
      codes.add("LOW_INVENTORY_WIDENED");
    }
    if (coldStart) {
      codes.add("COLD_START_HEURISTIC");
    }
    return codes;
  }

  /**
   * Whether the low-inventory widening applies: fewer strict survivors than {@code
   * low_inventory_min} and at least one candidate removed by the cooldown alone.
   */
  static boolean widened(Config c, int strictSurvivors, int cooledOnly) {
    return strictSurvivors < c.lowInventoryMin() && cooledOnly > 0;
  }

  /**
   * Scores the eligible candidates, sorts by score descending then ascending tie key and cuts to
   * {@code maxRoutes}.
   */
  static List<Result> rank(
      Config c,
      UUID userId,
      UUID sessionId,
      double targetGrade,
      List<Input> eligible,
      int maxRoutes,
      boolean widened,
      boolean coldStart) {
    List<Scored> scored = new ArrayList<>();
    for (Input in : eligible) {
      double fit = challengeFit(in.gradeNumeric(), targetGrade, c.gradeWindow());
      double confidence = confidence(in.gradeObservationCount(), c.minObs());
      scored.add(
          new Scored(
              in,
              fit,
              confidence,
              score(c, fit, in.novelty(), in.preferenceMatch(), confidence),
              tieKey(c.policyVersion(), userId, sessionId, in.routeVariantId())));
    }
    scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(Scored::tieKey));
    List<Result> results = new ArrayList<>();
    for (int i = 0; i < Math.min(maxRoutes, scored.size()); i++) {
      Scored s = scored.get(i);
      results.add(
          new Result(
              s.input().routeVariantId(),
              i + 1,
              s.score(),
              reasonCodes(
                  s.fit(),
                  s.input().novelty(),
                  s.input().preferenceMatch(),
                  s.confidence(),
                  widened,
                  coldStart)));
    }
    return results;
  }
}
