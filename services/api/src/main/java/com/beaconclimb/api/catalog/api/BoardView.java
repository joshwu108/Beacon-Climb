package com.beaconclimb.api.catalog.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** {@code Board}: holds in board geometry units, geometry frame and settable angles. */
public record BoardView(
    @JsonProperty("board_id") String boardId,
    String provider,
    @JsonProperty("layout_id") String layoutId,
    @JsonProperty("size_id") String sizeId,
    boolean synthetic,
    List<Hold> holds,
    Geometry geometry,
    @JsonProperty("available_angles") List<Integer> availableAngles) {

  /** {@code BoardHold}: {@code hold_id} is the provider hold id, never the internal uuid. */
  public record Hold(@JsonProperty("hold_id") String holdId, double x, double y) {}

  /** {@code BoardGeometry}, read back from {@code boards.geometry_json}. */
  public record Geometry(
      String units, double width, double height, @JsonProperty("y_axis") String yAxis) {}
}
