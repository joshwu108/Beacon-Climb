package com.beaconclimb.api.ranking;

import com.beaconclimb.api.identity.preferences.Goal;
import com.beaconclimb.api.identity.preferences.GradeBand;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Hand-built inputs shared by the ranking tests. */
final class RankingFixtures {

  static final Instant NOW = Instant.parse("2026-10-14T14:01:00Z");
  static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-000000000001");
  static final UUID SESSION = UUID.fromString("018f0000-0000-7000-8000-000000000002");
  static final String BOARD = "synthetic-demo-1";
  static final int ANGLE = 40;

  private RankingFixtures() {}

  static Policy policy() {
    return new PolicyLoader().load();
  }

  /** A policy with every weight but the two given set to zero and a flat target grade. */
  static Policy fixturePolicy(double challenge, double novelty, double targetGrade) {
    Map<GradeBand, Double> bands = new EnumMap<>(GradeBand.class);
    for (GradeBand band : GradeBand.values()) {
      bands.put(band, targetGrade);
    }
    Map<Goal, Double> offsets = new EnumMap<>(Goal.class);
    for (Goal goal : Goal.values()) {
      offsets.put(goal, 0.0);
    }
    return new Policy(
        "heuristic-v1",
        new Policy.Weights(challenge, 0.0, novelty, 0.0, 0.0),
        10,
        10,
        48,
        bands,
        offsets,
        Map.of(),
        3,
        "f".repeat(64));
  }

  static Candidate candidate(String id, Double grade) {
    return new Candidate(id, BOARD, ANGLE, grade, 0, Candidate.ACTIVE, List.of(), null);
  }

  static Candidate candidate(String id, Double grade, int observations, List<String> tags) {
    return new Candidate(id, BOARD, ANGLE, grade, observations, Candidate.ACTIVE, tags, null);
  }

  static Candidate attempted(String id, Double grade, Instant lastAttemptedAt) {
    return new Candidate(id, BOARD, ANGLE, grade, 0, Candidate.ACTIVE, List.of(), lastAttemptedAt);
  }

  static UserContext context(Goal goal, GradeBand band) {
    return context(goal, band, List.of(), Set.of(), null);
  }

  static UserContext context(
      Goal goal, GradeBand band, List<String> recent, Set<String> exclude, Double maxSent) {
    return new UserContext(USER, SESSION, BOARD, ANGLE, goal, band, recent, exclude, maxSent, NOW);
  }
}
