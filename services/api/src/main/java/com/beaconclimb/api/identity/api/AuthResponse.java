package com.beaconclimb.api.identity.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code AuthResponse}: the opaque bearer token and its RFC 3339 UTC expiry. {@link #toString()} is
 * redacted because Spring MVC logs response bodies at DEBUG (TM-16).
 */
public record AuthResponse(String token, @JsonProperty("expires_at") String expiresAt) {

  @Override
  public String toString() {
    return "AuthResponse[token=<redacted>, expiresAt=" + expiresAt + "]";
  }
}
