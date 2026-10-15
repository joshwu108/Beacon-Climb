package com.beaconclimb.api.catalog.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code Placement}: a hold of the variant with its role, in setter order. */
public record PlacementView(
    @JsonProperty("hold_id") String holdId, double x, double y, String role) {}
