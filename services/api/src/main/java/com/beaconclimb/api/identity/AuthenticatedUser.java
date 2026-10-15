package com.beaconclimb.api.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;

/**
 * The user resolved from the bearer token by {@link BearerAuthenticationFilter}, available to
 * controllers as a handler-method argument. {@code tokenHash} identifies the presented session for
 * logout; the raw token is never retained.
 */
public record AuthenticatedUser(UUID userId, String username, String tokenHash) {

  static final String ATTRIBUTE = AuthenticatedUser.class.getName();

  public static Optional<AuthenticatedUser> from(HttpServletRequest request) {
    return Optional.ofNullable(request.getAttribute(ATTRIBUTE))
        .filter(AuthenticatedUser.class::isInstance)
        .map(AuthenticatedUser.class::cast);
  }

  @Override
  public String toString() {
    return "AuthenticatedUser[userId=" + userId + "]";
  }
}
