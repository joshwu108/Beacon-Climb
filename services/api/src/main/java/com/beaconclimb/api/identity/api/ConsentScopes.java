package com.beaconclimb.api.identity.api;

/**
 * {@code ConsentScopes}: {@code service} must be {@code true}; {@code research} is opt-in and
 * defaults to {@code false} (C-07, TM-30). Wrapper types so a missing member is distinguishable
 * from {@code false} during validation.
 */
public record ConsentScopes(Boolean service, Boolean research) {

  public static ConsentScopes serviceOnly() {
    return new ConsentScopes(true, false);
  }

  public boolean researchGranted() {
    return Boolean.TRUE.equals(research);
  }
}
