package com.beaconclimb.api.platform.logging;

/**
 * Names shared between the request filters and the structured log lines (TM-16, TM-33). {@code
 * request_id} is set by {@code RequestIdFilter}; {@code user_id} is the pseudonymous user id set by
 * {@code BearerAuthenticationFilter} once the bearer token resolves, both in the MDC and as a
 * request attribute for filters outside the authenticated span. Nothing else about the user, the
 * headers or the body is ever a log field.
 */
public final class LogFields {

  public static final String REQUEST_ID = "request_id";
  public static final String USER_ID = "user_id";
  public static final String USER_ID_ATTRIBUTE = LogFields.class.getName() + ".userId";

  private LogFields() {}
}
