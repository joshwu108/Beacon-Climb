package com.beaconclimb.api.identity.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * {@code Me}: profile with the latest consent (revoked or not; null when none was ever granted) and
 * preferences. {@code preferences} is always serialised and stays {@code null} until BC-0108
 * introduces the Preferences record.
 */
public record MeResponse(
    @JsonProperty("user_id") UUID userId,
    String username,
    ConsentView consent,
    Object preferences) {

  /** Spring MVC logs response bodies at DEBUG; logs may carry the user_id only (TM-16). */
  @Override
  public String toString() {
    return "MeResponse[userId=" + userId + ", username=<redacted>]";
  }
}
