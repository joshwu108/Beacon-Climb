package com.beaconclimb.api.modeloutputs.api;

import com.beaconclimb.api.catalog.api.RouteVariantSummaryView;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** {@code SimilarRoutes} of contracts/openapi.yaml 1.3.0 (BC-0210). */
public record SimilarRoutesResponse(
    @JsonProperty("route_variant_id") String routeVariantId,
    @JsonProperty("model_version") String modelVersion,
    List<Item> items) {

  /** One neighbour: its summary and the cosine distance to the requested route. */
  public record Item(RouteVariantSummaryView route, double distance) {}
}
