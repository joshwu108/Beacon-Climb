package com.beaconclimb.api.identity.api;

/**
 * {@code AuthRequest} (contract 1.1.0): username {@code ^[a-z0-9_]{3,32}$}, password 8–128
 * characters. Validation lives in {@code AuthService} so field errors carry the contract keywords.
 * {@link #toString()} is redacted because Spring MVC logs request bodies at DEBUG (TM-16).
 */
public record AuthRequest(String username, String password) {

  @Override
  public String toString() {
    return "AuthRequest[redacted]";
  }
}
