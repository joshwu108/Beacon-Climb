package com.beaconclimb.api.attempts;

import com.beaconclimb.api.platform.web.RequestIdFilter;
import org.slf4j.MDC;

/**
 * {@code trace_id} of an outbox event: the current request id from the MDC (set by {@link
 * RequestIdFilter}) or a fixed marker outside an HTTP request (jobs, in-process tests).
 */
final class TraceIds {

  static final String UNTRACED = "untraced";

  private TraceIds() {}

  static String current() {
    String requestId = MDC.get(RequestIdFilter.MDC_KEY);
    return requestId == null || requestId.isBlank() ? UNTRACED : requestId;
  }
}
