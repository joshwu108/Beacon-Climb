package com.beaconclimb.api.attempts;

/**
 * A shape-valid {@code AttemptPatch}: each member null when absent. {@code retract} is only ever
 * true, and never combined with another member ({@link AttemptPatchValidator}).
 */
public record AttemptPatch(
    Integer attemptsCount,
    Boolean sent,
    Double completionFraction,
    Integer perceivedEffort,
    String notes,
    boolean retract) {

  @Override
  public String toString() {
    return "AttemptPatch[retract=" + retract + "]";
  }
}
