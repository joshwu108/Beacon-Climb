package com.beaconclimb.api.catalog.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** {@code RouteVariant}: the summary plus placements and tags; {@code missing_tags} = no tags. */
public record RouteVariantView(
    @JsonProperty("route_variant_id") String routeVariantId,
    @JsonProperty("board_id") String boardId,
    @JsonProperty("angle_deg") int angleDeg,
    String name,
    @JsonProperty("grade_numeric") Double gradeNumeric,
    @JsonProperty("grade_label") String gradeLabel,
    @JsonProperty("grade_observation_count") int gradeObservationCount,
    List<PlacementView> placements,
    List<String> tags,
    @JsonProperty("missing_tags") boolean missingTags) {}
