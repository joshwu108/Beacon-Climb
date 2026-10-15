package com.beaconclimb.api.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes an {@link ErrorEnvelope} from a servlet filter, where no {@code @ControllerAdvice} runs.
 * The buffer is reset (never the headers, so {@code X-Request-Id} survives) and nothing is written
 * once the response is committed. The message is the caller's client-facing text; exception text
 * never passes through here.
 */
@Component
public class ErrorResponseWriter {

  private final JsonMapper json;

  public ErrorResponseWriter(JsonMapper json) {
    this.json = json;
  }

  public void write(
      HttpServletRequest request,
      HttpServletResponse response,
      HttpStatus status,
      ErrorCode code,
      String message,
      Map<String, String> headers)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    response.resetBuffer();
    response.setStatus(status.value());
    headers.forEach(response::setHeader);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    ErrorEnvelope body =
        ErrorEnvelope.of(code, message, RequestIdFilter.current(request), List.of());
    response.getWriter().write(json.writeValueAsString(body));
    response.flushBuffer();
  }
}
