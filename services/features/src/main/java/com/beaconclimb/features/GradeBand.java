package com.beaconclimb.features;

/**
 * {@code GradeBand}: coarse self-assessed band from contracts/openapi.yaml 1.1.0 (A4, A6). It is a
 * ranking input only, never a claim about physical difficulty or ability.
 */
public enum GradeBand {
  NOVICE,
  BEGINNER,
  INTERMEDIATE,
  ADVANCED,
  EXPERT
}
