package com.beaconclimb.api.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Maps every failure to the contract {@link ErrorEnvelope}. Seed for BC-0122. Rules:
 *
 * <ul>
 *   <li>{@link ApiException}: its status, code and field errors.
 *   <li>Unknown JSON member (Jackson {@code FAIL_ON_UNKNOWN_PROPERTIES}): 422 UNKNOWN_FIELD.
 *   <li>Missing or malformed body, wrong media type, missing header: 422 VALIDATION_FAILED. The
 *       contract declares no 400 on the auth operations, so malformed bodies are 422 everywhere.
 *   <li>Unknown route or method: 404 NOT_FOUND (no enumeration of methods on reserved paths).
 *   <li>Anything else: 500 INTERNAL_ERROR; the stack trace goes to the log with the request id,
 *       never to the client.
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ErrorEnvelope> apiException(ApiException ex, HttpServletRequest request) {
    ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.status());
    ex.headers().forEach(builder::header);
    return builder
        .contentType(MediaType.APPLICATION_JSON)
        .body(envelope(ex.code(), ex.getMessage(), ex.fieldErrors(), request));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ErrorEnvelope> unreadable(
      HttpMessageNotReadableException ex, HttpServletRequest request) {
    UnrecognizedPropertyException unknown = findUnknownProperty(ex);
    if (unknown != null) {
      FieldError error = new FieldError(jsonPath(unknown), "additionalProperties", "unknown field");
      return respond(
          HttpStatus.UNPROCESSABLE_CONTENT,
          ErrorCode.UNKNOWN_FIELD,
          "Request contains an unknown field",
          List.of(error),
          request);
    }
    boolean missing =
        ex.getMessage() != null && ex.getMessage().startsWith("Required request body");
    FieldError error =
        missing
            ? new FieldError("body", "required", "request body is required")
            : new FieldError("body", "malformed", "request body is not valid JSON");
    return validationFailed(List.of(error), request);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ErrorEnvelope> mediaType(HttpServletRequest request) {
    return validationFailed(
        List.of(new FieldError("Content-Type", "enum", "must be application/json")), request);
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  ResponseEntity<ErrorEnvelope> missingHeader(
      MissingRequestHeaderException ex, HttpServletRequest request) {
    return validationFailed(
        List.of(new FieldError(ex.getHeaderName(), "required", "header is required")), request);
  }

  @ExceptionHandler({
    NoResourceFoundException.class,
    NoHandlerFoundException.class,
    HttpRequestMethodNotSupportedException.class
  })
  ResponseEntity<ErrorEnvelope> notFound(HttpServletRequest request) {
    return respond(
        HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "No such resource", List.of(), request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorEnvelope> unexpected(Exception ex, HttpServletRequest request) {
    log.error("Unhandled exception request_id={}", RequestIdFilter.current(request), ex);
    return respond(
        HttpStatus.INTERNAL_SERVER_ERROR,
        ErrorCode.INTERNAL_ERROR,
        "Internal error",
        List.of(),
        request);
  }

  private static ResponseEntity<ErrorEnvelope> validationFailed(
      List<FieldError> errors, HttpServletRequest request) {
    return respond(
        HttpStatus.UNPROCESSABLE_CONTENT,
        ErrorCode.VALIDATION_FAILED,
        "Request failed validation",
        errors,
        request);
  }

  private static ResponseEntity<ErrorEnvelope> respond(
      HttpStatus status,
      ErrorCode code,
      String message,
      List<FieldError> errors,
      HttpServletRequest request) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(envelope(code, message, errors, request));
  }

  private static ErrorEnvelope envelope(
      ErrorCode code, String message, List<FieldError> errors, HttpServletRequest request) {
    return ErrorEnvelope.of(code, message, RequestIdFilter.current(request), errors);
  }

  private static UnrecognizedPropertyException findUnknownProperty(Throwable ex) {
    for (Throwable t = ex; t != null; t = t.getCause()) {
      if (t instanceof UnrecognizedPropertyException unknown) {
        return unknown;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return null;
  }

  /** Dotted JSON path of the offending member, e.g. {@code scopes.extra}. */
  private static String jsonPath(UnrecognizedPropertyException ex) {
    StringBuilder path = new StringBuilder();
    for (var ref : ex.getPath()) {
      if (ref.getPropertyName() != null) {
        if (!path.isEmpty()) {
          path.append('.');
        }
        path.append(ref.getPropertyName());
      } else if (ref.getIndex() >= 0) {
        path.append('[').append(ref.getIndex()).append(']');
      }
    }
    return path.isEmpty() ? String.valueOf(ex.getPropertyName()) : path.toString();
  }
}
