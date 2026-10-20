package com.beaconclimb.api.ranking;

import java.util.List;

/**
 * PRD 7.4 scoring as fixed by planning/06 section 9. The arithmetic order is part of the replay
 * contract (ADR-0003: IEEE-754 bit patterns must match): each product is formed first, then the
 * five products are summed strictly left to right, challenge, skill gap, novelty, preference,
 * confidence, exactly as {@link #score} is written. {@code skill_gap} is the constant 0 in v1.
 */
public final class Scoring {

  /** v1 has no skill-gap signal; the weight multiplies this constant. */
  static final double SKILL_GAP = 0.0;

  static final double CHALLENGE_MATCH_MIN_FIT = 0.5;

  private Scoring() {}

  public static ScoreInputs inputs(Candidate candidate, UserContext context, Policy policy) {
    double targetGrade = policy.targetGrade(context.gradeBand(), context.goal());
    return new ScoreInputs(
        targetGrade,
        challengeFit(candidate.gradeNumeric(), targetGrade, policy.gradeWindow()),
        context.recentVariantIds().contains(candidate.routeVariantId()) ? 0.0 : 1.0,
        preferenceMatch(candidate.tags(), policy.preferredTagsFor(context.goal())),
        Math.min(1.0, (double) candidate.gradeObservationCount() / policy.minObs()));
  }

  /**
   * heuristic-v2 (BC-0210): the same five inputs, with the challenge fit scored on {@link
   * #gradeUsed} and novelty from {@link #embeddingDistance}. A policy without a {@code model} block
   * takes the v1 path above unchanged, so v1 decisions keep their exact scores.
   */
  public static ScoreInputs inputs(
      Candidate candidate, UserContext context, Policy policy, ModelSignals signals) {
    if (!policy.usesModel()) {
      return inputs(candidate, context, policy);
    }
    ModelSettings model = policy.model();
    double targetGrade = policy.targetGrade(context.gradeBand(), context.goal());
    Double distance = embeddingDistance(candidate.routeVariantId(), signals);
    return new ScoreInputs(
        targetGrade,
        challengeFit(gradeUsed(candidate, signals, model), targetGrade, policy.gradeWindow()),
        noveltyV2(candidate, context, distance, model),
        preferenceMatch(candidate.tags(), policy.preferredTagsFor(context.goal())),
        Math.min(1.0, (double) candidate.gradeObservationCount() / policy.minObs()));
  }

  /**
   * {@code (n * community + k * predicted) / (n + k)} with {@code n} the ascent count and {@code k
   * = blend_k}, formed in exactly this order; the community grade alone when no prediction exists,
   * the prediction alone when the variant is ungraded. Null only when neither exists.
   */
  public static Double gradeUsed(Candidate candidate, ModelSignals signals, ModelSettings model) {
    Double community = candidate.gradeNumeric();
    Double predicted = signals.predictedGrade().get(candidate.routeVariantId());
    if (predicted == null) {
      return community;
    }
    if (community == null) {
      return predicted;
    }
    double n = candidate.gradeObservationCount();
    double k = model.blendK();
    double numerator = n * community + k * predicted;
    return numerator / (n + k);
  }

  /**
   * Smallest cosine distance between this candidate's embedding and the embeddings of the user's
   * recent routes, scanned in recent order; null when either side has no embedding.
   */
  public static Double embeddingDistance(String routeVariantId, ModelSignals signals) {
    float[] own = signals.embedding().get(routeVariantId);
    if (own == null || signals.recentEmbeddings().isEmpty()) {
      return null;
    }
    double best = Double.POSITIVE_INFINITY;
    for (float[] recent : signals.recentEmbeddings()) {
      best = Math.min(best, cosineDistance(own, recent));
    }
    return best;
  }

  /** {@code 1 - cos(a, b)} in [0, 2], accumulated in double in index order; 1 for a zero vector. */
  public static double cosineDistance(float[] a, float[] b) {
    if (a.length != b.length) {
      throw new IllegalArgumentException("embedding dimensions differ");
    }
    double dot = 0;
    double na = 0;
    double nb = 0;
    for (int i = 0; i < a.length; i++) {
      dot += (double) a[i] * b[i];
      na += (double) a[i] * a[i];
      nb += (double) b[i] * b[i];
    }
    if (na == 0 || nb == 0) {
      return 1.0;
    }
    double cosine = dot / (Math.sqrt(na) * Math.sqrt(nb));
    return 1.0 - Math.max(-1.0, Math.min(1.0, cosine));
  }

  /**
   * 0 for a route in the recent list (as v1); otherwise {@code min(1, distance / scale)}, 1 without
   * one.
   */
  static double noveltyV2(
      Candidate candidate, UserContext context, Double distance, ModelSettings model) {
    if (context.recentVariantIds().contains(candidate.routeVariantId())) {
      return 0.0;
    }
    if (distance == null) {
      return 1.0;
    }
    return Math.min(1.0, distance / model.noveltyDistanceScale());
  }

  /** {@code 1 - min(1, |grade - target| / grade_window)}; an ungraded candidate scores 0 here. */
  static double challengeFit(Double gradeNumeric, double targetGrade, double gradeWindow) {
    if (gradeNumeric == null) {
      return 0.0;
    }
    return 1.0 - Math.min(1.0, Math.abs(gradeNumeric - targetGrade) / gradeWindow);
  }

  static double preferenceMatch(List<String> tags, List<String> preferred) {
    for (String tag : tags) {
      if (preferred.contains(tag)) {
        return 1.0;
      }
    }
    return 0.0;
  }

  public static double score(ScoreInputs in, Policy.Weights w) {
    double score = w.challenge() * in.challengeFit();
    score = score + w.skillGap() * SKILL_GAP;
    score = score + w.novelty() * in.novelty();
    score = score + w.preference() * in.preferenceMatch();
    score = score + w.confidence() * in.confidenceBonus();
    return score;
  }
}
