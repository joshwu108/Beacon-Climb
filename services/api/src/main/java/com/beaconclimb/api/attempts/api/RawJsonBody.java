package com.beaconclimb.api.attempts.api;

import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.FieldError;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Reads the request body as text without Spring's message converters, so (a) the idempotency
 * fingerprint is taken over the client's own JSON text rather than a re-serialised DTO (ADR-0002)
 * and (b) Spring MVC never logs the body at DEBUG (TM-10, TM-16). The bounded stream installed by
 * {@code ContentLengthLimitFilter} still applies, so an oversized body is 413. The charset is the
 * one declared on {@code Content-Type}, otherwise UTF-8 (RFC 8259).
 */
final class RawJsonBody {

  private RawJsonBody() {}

  static String read(HttpServletRequest request) throws IOException {
    byte[] bytes = request.getInputStream().readAllBytes();
    return new String(bytes, charset(request.getCharacterEncoding()));
  }

  private static Charset charset(String declared) {
    if (declared == null || declared.isBlank()) {
      return StandardCharsets.UTF_8;
    }
    try {
      return Charset.forName(declared);
    } catch (IllegalArgumentException e) {
      throw ApiException.validationFailed(
          List.of(new FieldError("Content-Type", "charset", "unsupported charset")));
    }
  }
}
