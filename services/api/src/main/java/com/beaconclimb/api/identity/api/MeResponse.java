package com.beaconclimb.api.identity.api;

import com.beaconclimb.api.identity.preferences.PreferencesView;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * {@code Me}: profile with the latest consent (revoked or not; null when none was ever granted) and
 * preferences. {@code preferences} is always serialised: {@code null} until the user has put some
 * (BC-0108), the contract {@code Preferences} object afterwards.
 */
public record MeResponse(
    @JsonProperty("user_id") UUID userId,
    String username,
    ConsentView consent,
    PreferencesView preferences) {

  /** Spring MVC logs response bodies at DEBUG; logs may carry the user_id only (TM-16). */
  @Override
  public String toString() {
    return "MeResponse[userId=" + userId + ", username=<redacted>]";
  }
}
