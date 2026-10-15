package com.beaconclimb.api.catalog.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** {@code RouteList}: one page and the opaque cursor of the next one (null on the last page). */
public record RouteListResponse(
    List<RouteVariantSummaryView> items, @JsonProperty("next_cursor") String nextCursor) {}
