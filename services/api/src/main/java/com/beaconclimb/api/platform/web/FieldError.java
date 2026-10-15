package com.beaconclimb.api.platform.web;

/**
 * One entry of {@code ErrorEnvelope.field_errors}. {@code field} is the JSON path of the offending
 * member or the parameter/header name; {@code code} is a validator keyword such as required,
 * pattern, minLength, maxLength, const, unique or additionalProperties (contract 1.1.0).
 */
public record FieldError(String field, String code, String message) {}
