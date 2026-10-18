package com.beaconclimb.features;

/**
 * Fixed mapping from a variant's {@code grade_numeric} to the {@code send_rate_by_grade_band_90d}
 * key (contracts/features/climber-features.v1.md section 4). Edges are half-open on grade_numeric:
 * NOVICE below 14, BEGINNER [14, 18), INTERMEDIATE [18, 22), ADVANCED [22, 26), EXPERT 26 and
 * above; an ungraded variant goes to {@link #UNGRADED}. The edges are planning placeholders pending
 * the grade scale contract and claim nothing about physical difficulty.
 */
public final class GradeBandBuckets {

  public static final String UNGRADED = "UNGRADED";
  static final double BEGINNER_FROM = 14;
  static final double INTERMEDIATE_FROM = 18;
  static final double ADVANCED_FROM = 22;
  static final double EXPERT_FROM = 26;

  private GradeBandBuckets() {}

  public static String keyFor(Double gradeNumeric) {
    return gradeNumeric == null ? UNGRADED : bandFor(gradeNumeric).name();
  }

  public static GradeBand bandFor(double gradeNumeric) {
    if (gradeNumeric < BEGINNER_FROM) {
      return GradeBand.NOVICE;
    }
    if (gradeNumeric < INTERMEDIATE_FROM) {
      return GradeBand.BEGINNER;
    }
    if (gradeNumeric < ADVANCED_FROM) {
      return GradeBand.INTERMEDIATE;
    }
    if (gradeNumeric < EXPERT_FROM) {
      return GradeBand.ADVANCED;
    }
    return GradeBand.EXPERT;
  }
}
