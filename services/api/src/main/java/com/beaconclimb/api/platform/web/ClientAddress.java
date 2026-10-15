package com.beaconclimb.api.platform.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The client address used for per-IP limits: the socket peer, unless that peer is one of {@code
 * beacon.http.trusted-proxies}, in which case the first non-blank {@code X-Forwarded-For} entry
 * wins (BC-0123 F3). Only a proxy that overwrites the header (the compose nginx sets it from {@code
 * $remote_addr}) may be listed; behind one that appends, the first entry is client-chosen. The
 * default list is empty, so with no configuration every peer is its own limiter key.
 */
public final class ClientAddress {

  static final String FORWARDED_FOR = "X-Forwarded-For";

  private ClientAddress() {}

  public static String of(HttpServletRequest request, TrustedProxies trustedProxies) {
    String peer = request.getRemoteAddr();
    if (trustedProxies.contains(peer)) {
      String first = firstEntry(request.getHeader(FORWARDED_FOR));
      if (first != null) {
        return first;
      }
    }
    return peer;
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
