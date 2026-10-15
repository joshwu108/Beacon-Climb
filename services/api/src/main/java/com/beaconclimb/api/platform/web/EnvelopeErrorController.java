package com.beaconclimb.api.platform.web;

import com.beaconclimb.api.platform.web.StatusCodes.Mapping;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces Boot's {@code BasicErrorController} so errors raised below Spring MVC (Tomcat's own 400
 * for a malformed request, {@code sendError} from a filter, an exception escaping the dispatcher)
 * still render the contract {@link ErrorEnvelope} with the request id instead of Boot's default
 * JSON. The original request attributes survive the error dispatch, so the id is the one echoed in
 * {@code X-Request-Id}. A direct call to {@code /error} is a plain 404.
 */
@RestController
public class EnvelopeErrorController implements ErrorController {

  @RequestMapping("${server.error.path:${error.path:/error}}")
  public ResponseEntity<ErrorEnvelope> error(HttpServletRequest request) {
    Mapping mapping = StatusCodes.forStatus(status(request));
    ErrorEnvelope body =
        ErrorEnvelope.of(
            mapping.code(), mapping.message(), RequestIdFilter.current(request), List.of());
    return ResponseEntity.status(mapping.status())
        .contentType(MediaType.APPLICATION_JSON)
        .body(body);
  }

  private static HttpStatus status(HttpServletRequest request) {
    Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    HttpStatus status = code instanceof Integer value ? HttpStatus.resolve(value) : null;
    return status == null ? HttpStatus.NOT_FOUND : status;
  }
}
