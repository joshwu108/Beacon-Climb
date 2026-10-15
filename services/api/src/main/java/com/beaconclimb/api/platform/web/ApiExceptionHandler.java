package com.beaconclimb.api.platform.web;

import com.beaconclimb.api.platform.web.StatusCodes.Mapping;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Maps every failure that reaches Spring MVC to the contract {@link ErrorEnvelope} (BC-0122).
 * Rules:
 *
 * <ul>
 *   <li>{@link ApiException}: its status, code, field errors and headers.
 *   <li>Unknown JSON member (Jackson {@code FAIL_ON_UNKNOWN_PROPERTIES}): 422 UNKNOWN_FIELD.
 *   <li>Wrong JSON type or enum value: 422 VALIDATION_FAILED with the member path and a {@code
 *       type} or {@code enum} keyword; missing or malformed body, wrong media type, missing header
 *       or parameter, parameter type mismatch: 422 VALIDATION_FAILED. The contract declares no 400
 *       or 415 on these operations, so they are 422 everywhere.
 *   <li>Body past the limit (chunked, caught by the bounded stream): 413 PAYLOAD_TOO_LARGE.
 *   <li>Unknown route or method: 404 NOT_FOUND (no enumeration of methods on reserved paths).
 *   <li>Unacceptable {@code Accept}: 406 VALIDATION_FAILED, body still JSON.
 *   <li>{@link ErrorResponseException} (e.g. {@code ResponseStatusException}): status mapped by
 *       {@link StatusCodes}; the exception reason never reaches the client.
 *   <li>Anything else: 500 INTERNAL_ERROR with a generic message; the stack trace goes to the log
 *       at ERROR with the request id, never to the client.
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
    if (findCause(ex, PayloadTooLargeException.class) != null) {
      return respond(
          HttpStatus.CONTENT_TOO_LARGE,
          ErrorCode.PAYLOAD_TOO_LARGE,
          "Request body is too large",
          List.of(),
          request);
    }
    UnrecognizedPropertyException unknown = findCause(ex, UnrecognizedPropertyException.class);
    if (unknown != null) {
      FieldError error =
          new FieldError(
              jsonPath(unknown, unknown.getPropertyName()),
              "additionalProperties",
              "unknown field");
      return respond(
          HttpStatus.UNPROCESSABLE_CONTENT,
          ErrorCode.UNKNOWN_FIELD,
          "Request contains an unknown field",
          List.of(error),
          request);
    }
    MismatchedInputException mismatch = findCause(ex, MismatchedInputException.class);
    if (mismatch != null) {
      return validationFailed(List.of(typeError(mismatch)), request);
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

  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  ResponseEntity<ErrorEnvelope> notAcceptable(HttpServletRequest request) {
    return respond(
        HttpStatus.NOT_ACCEPTABLE,
        ErrorCode.VALIDATION_FAILED,
        "Request failed validation",
        List.of(new FieldError("Accept", "enum", "must accept application/json")),
        request);
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  ResponseEntity<ErrorEnvelope> missingHeader(
      MissingRequestHeaderException ex, HttpServletRequest request) {
    return validationFailed(
        List.of(new FieldError(ex.getHeaderName(), "required", "header is required")), request);
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<ErrorEnvelope> missingParameter(
      MissingServletRequestParameterException ex, HttpServletRequest request) {
    return validationFailed(
        List.of(new FieldError(ex.getParameterName(), "required", "parameter is required")),
        request);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ErrorEnvelope> typeMismatch(
      MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
    String field = ex.getName() == null ? "parameter" : ex.getName();
    return validationFailed(
        List.of(new FieldError(field, "type", "must be " + jsonTypeName(ex.getRequiredType()))),
        request);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorEnvelope> notValid(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    List<FieldError> errors = new ArrayList<>();
    for (org.springframework.validation.FieldError error : ex.getBindingResult().getFieldErrors()) {
      errors.add(new FieldError(error.getField(), "invalid", "is invalid"));
    }
    if (errors.isEmpty()) {
      errors.add(new FieldError("body", "invalid", "is invalid"));
    }
    return validationFailed(errors, request);
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  ResponseEntity<ErrorEnvelope> handlerValidation(HttpServletRequest request) {
    return validationFailed(
        List.of(new FieldError("parameters", "invalid", "are invalid")), request);
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

  @ExceptionHandler(ErrorResponseException.class)
  ResponseEntity<ErrorEnvelope> errorResponse(
      ErrorResponseException ex, HttpServletRequest request) {
    HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
    if (status == null) {
      return unexpected(ex, request);
    }
    Mapping mapping = StatusCodes.forStatus(status);
    if (mapping.code() == ErrorCode.INTERNAL_ERROR) {
      return unexpected(ex, request);
    }
    log.info(
        "Error response status={} request_id={}", status.value(), RequestIdFilter.current(request));
    return respond(mapping.status(), mapping.code(), mapping.message(), List.of(), request);
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

  private static <T extends Throwable> T findCause(Throwable ex, Class<T> type) {
    for (Throwable t = ex; t != null; t = t.getCause()) {
      if (type.isInstance(t)) {
        return type.cast(t);
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return null;
  }

  /** A wrong JSON type or an unknown enum constant, located by its member path. */
  private static FieldError typeError(MismatchedInputException ex) {
    String field = jsonPath(ex, "body");
    Class<?> target = ex.getTargetType();
    if (ex instanceof InvalidFormatException && target != null && target.isEnum()) {
      String allowed =
          Arrays.stream(target.getEnumConstants())
              .map(Object::toString)
              .collect(Collectors.joining(", "));
      return new FieldError(field, "enum", "must be one of " + allowed);
    }
    return new FieldError(field, "type", "must be " + jsonTypeName(target));
  }

  private static String jsonTypeName(Class<?> type) {
    if (type == null) {
      return "valid";
    }
    if (type == String.class || type.isEnum()) {
      return "string";
    }
    if (type == Boolean.class || type == boolean.class) {
      return "boolean";
    }
    if (type == Integer.class
        || type == int.class
        || type == Long.class
        || type == long.class
        || type == Short.class
        || type == short.class) {
      return "integer";
    }
    if (Number.class.isAssignableFrom(type) || type == double.class || type == float.class) {
      return "number";
    }
    if (Collection.class.isAssignableFrom(type) || type.isArray()) {
      return "array";
    }
    return "object";
  }

  /** Dotted JSON path of the offending member, e.g. {@code scopes.extra} or {@code goals[2]}. */
  private static String jsonPath(DatabindException ex, String fallback) {
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
    return path.isEmpty() ? String.valueOf(fallback) : path.toString();
  }
}
