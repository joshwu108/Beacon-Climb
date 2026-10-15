package com.beaconclimb.api.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns every request an id: the incoming {@code X-Request-Id} when it is sane, otherwise a fresh
 * UUID. The id is echoed as a response header before the chain runs (so 401s from the
 * authentication filter and actuator responses carry it too), stored as a request attribute for the
 * error envelope and put in the MDC as {@code request_id}. Runs first; seed for BC-0122.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Request-Id";
  public static final String MDC_KEY = "request_id";
  static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
  private static final Pattern SANE = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String requestId = resolve(request.getHeader(HEADER));
    request.setAttribute(ATTRIBUTE, requestId);
    response.setHeader(HEADER, requestId);
    MDC.put(MDC_KEY, requestId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  /** Request id of the current request, or {@code unknown} outside the filter chain. */
  public static String current(HttpServletRequest request) {
    Object value = request.getAttribute(ATTRIBUTE);
    return value == null ? "unknown" : value.toString();
  }

  static String resolve(String incoming) {
    if (incoming != null && SANE.matcher(incoming).matches()) {
      return incoming;
    }
    return UUID.randomUUID().toString();
  }
}
