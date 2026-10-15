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
