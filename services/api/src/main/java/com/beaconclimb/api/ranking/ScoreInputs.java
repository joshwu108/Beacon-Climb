package com.beaconclimb.api.ranking;

/**
 * The per-candidate scoring inputs exactly as fed to {@link Scoring#score}; stored with the
 * decision's candidate snapshot (BC-0111) so a replay re-ranks from frozen values.
 */
public record ScoreInputs(
    double targetGrade,
    double challengeFit,
    double novelty,
    double preferenceMatch,
    double confidenceBonus) {}
