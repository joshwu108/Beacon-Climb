package com.beaconclimb.api.modeloutputs.api;

import com.beaconclimb.api.identity.AuthenticatedUser;
import com.beaconclimb.api.modeloutputs.SimilarRoutesService;
import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.FieldError;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code listSimilarRoutes} (contracts/openapi.yaml 1.3.0, BC-0210). */
@RestController
public class SimilarRoutesController {

  static final int DEFAULT_LIMIT = 10;
  static final int MAX_LIMIT = 50;

  private final SimilarRoutesService similar;

  public SimilarRoutesController(SimilarRoutesService similar) {
    this.similar = similar;
  }

  @GetMapping(
      path = "/v1/routes/{route_variant_id}/similar",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public SimilarRoutesResponse listSimilarRoutes(
      AuthenticatedUser user,
      @PathVariable("route_variant_id") String routeVariantId,
      @RequestParam(name = "limit", required = false) String limit) {
    return similar
        .similar(routeVariantId, limit(limit))
        .orElseThrow(
            () ->
                new ApiException(
                    ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "No such route variant"));
  }

  static int limit(String raw) {
    if (raw == null) {
      return DEFAULT_LIMIT;
    }
    int value;
    try {
      value = Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      throw ApiException.validationFailed(
          List.of(new FieldError("limit", "type", "must be an integer")));
    }
    if (value < 1) {
      throw ApiException.validationFailed(
          List.of(new FieldError("limit", "minimum", "must be >= 1")));
    }
    if (value > MAX_LIMIT) {
      throw ApiException.validationFailed(
          List.of(new FieldError("limit", "maximum", "must be <= " + MAX_LIMIT)));
    }
    return value;
  }
}
