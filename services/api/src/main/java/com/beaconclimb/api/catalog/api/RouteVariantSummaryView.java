package com.beaconclimb.api.catalog.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code RouteVariantSummary}. {@code grade_numeric} is a JSON number or null; {@code grade_label}
 * is always null until the label table (contracts/catalog/grade-scale.v1.json) is unblocked.
 */
public record RouteVariantSummaryView(
    @JsonProperty("route_variant_id") String routeVariantId,
    @JsonProperty("board_id") String boardId,
    @JsonProperty("angle_deg") int angleDeg,
    String name,
    @JsonProperty("grade_numeric") Double gradeNumeric,
    @JsonProperty("grade_label") String gradeLabel,
    @JsonProperty("grade_observation_count") int gradeObservationCount) {}
