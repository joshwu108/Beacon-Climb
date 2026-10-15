package com.beaconclimb.api.platform.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The client address used for per-IP limits: the socket peer, unless {@code
 * beacon.http.trust-forwarded-headers} is on, in which case the first non-blank {@code
 * X-Forwarded-For} entry wins. Trusting that header is only safe behind a reverse proxy that
 * overwrites it (otherwise any client picks its own limiter key), so the default is off and the
 * compose stack publishes the api port on 127.0.0.1 only.
 */
public final class ClientAddress {

  static final String FORWARDED_FOR = "X-Forwarded-For";

  private ClientAddress() {}

  public static String of(HttpServletRequest request, boolean trustForwardedHeaders) {
    if (trustForwardedHeaders) {
      String forwarded = request.getHeader(FORWARDED_FOR);
      String first = firstEntry(forwarded);
      if (first != null) {
        return first;
      }
    }
    return request.getRemoteAddr();
  }

  static String firstEntry(String forwardedFor) {
    if (forwardedFor == null) {
      return null;
    }
    int comma = forwardedFor.indexOf(',');
    String first = (comma < 0 ? forwardedFor : forwardedFor.substring(0, comma)).trim();
    return first.isEmpty() ? null : first;
  }
}
