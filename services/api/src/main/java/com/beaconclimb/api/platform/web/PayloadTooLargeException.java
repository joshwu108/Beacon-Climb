package com.beaconclimb.api.platform.web;

import java.io.IOException;

/**
 * Thrown by {@link ContentLengthLimitFilter}'s bounded stream when a body without a usable {@code
 * Content-Length} grows past {@code beacon.http.max-body-bytes}. It surfaces inside Spring's {@code
 * HttpMessageNotReadableException} cause chain and is rendered as 413 PAYLOAD_TOO_LARGE.
 */
public class PayloadTooLargeException extends IOException {

  public PayloadTooLargeException(long limit) {
    super("request body exceeds " + limit + " bytes");
  }
}
