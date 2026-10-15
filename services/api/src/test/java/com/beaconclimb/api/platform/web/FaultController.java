package com.beaconclimb.api.platform.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Test-only endpoints (src/test, never in the jar) that provoke failures Spring MVC must render as
 * envelopes: an unexpected exception, a path variable and a query parameter that fail conversion,
 * and a {@code ResponseStatusException}. The marker strings must never appear in a response.
 */
@RestController
public class FaultController {

  public static final String BOOM_MARKER = "fault-marker-must-not-leak";
  public static final String REASON_MARKER = "reason-marker-must-not-leak";

  @GetMapping("/v1/_faults/boom")
  public String boom() {
    throw new IllegalStateException(BOOM_MARKER);
  }

  @GetMapping("/v1/_faults/int/{n}")
  public String typedPath(@PathVariable int n) {
    return "{\"n\":" + n + "}";
  }

  @GetMapping("/v1/_faults/param")
  public String typedParam(@RequestParam int n) {
    return "{\"n\":" + n + "}";
  }

  @GetMapping("/v1/_faults/unavailable")
  public String unavailable() {
    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, REASON_MARKER);
  }
}
