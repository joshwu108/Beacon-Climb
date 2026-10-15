package com.beaconclimb.api.catalog.importer;

import com.beaconclimb.api.catalog.importer.CatalogRecords.Board;
import com.beaconclimb.api.catalog.importer.CatalogRecords.Hold;
import com.beaconclimb.api.catalog.importer.CatalogRecords.Placement;
import com.beaconclimb.api.catalog.importer.CatalogRecords.Route;
import com.beaconclimb.api.catalog.importer.CatalogRecords.Variant;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Cross-line rules of contracts/catalog/README.md (record order, references, uniqueness, numeric
 * ranges of the target columns). Reason codes mirror the reference validator. State is the set of
 * boards and routes accepted so far in this file, so a rejected board also makes its routes
 * UNKNOWN_BOARD.
 */
final class CatalogValidator {

  /** Accepted board: hold ids by provider_hold_id and the angles routes may use. */
  record BoardState(Map<String, UUID> holdIds, Set<Integer> angles) {}

  /** numeric(8,3) holds coordinates; numeric(5,2) grades. */
  private static final BigDecimal COORDINATE_LIMIT = new BigDecimal("100000");

  private static final BigDecimal GRADE_LIMIT = new BigDecimal("1000");

  private final boolean manifestSynthetic;
  private final Map<String, BoardState> boards = new HashMap<>();
  private final Set<String> routeKeys = new HashSet<>();
  private boolean routesStarted;

  CatalogValidator(boolean manifestSynthetic) {
    this.manifestSynthetic = manifestSynthetic;
  }

  Optional<String> checkBoard(Board board) {
    if (routesStarted) {
      return Optional.of("BOARD_AFTER_ROUTE");
    }
    if (boards.containsKey(board.boardId())) {
      return Optional.of("DUPLICATE_BOARD");
    }
    if (board.synthetic() != manifestSynthetic) {
      return Optional.of("BOARD_SYNTHETIC_MISMATCH board.synthetic must equal manifest.synthetic");
    }
    Set<String> seen = new HashSet<>();
    for (Hold hold : board.holds()) {
      if (!seen.add(hold.providerHoldId())) {
        return Optional.of("DUPLICATE_HOLD " + hold.providerHoldId());
      }
      if (exceeds(hold.x(), COORDINATE_LIMIT) || exceeds(hold.y(), COORDINATE_LIMIT)) {
        return Optional.of("HOLD_COORDINATE_OUT_OF_RANGE " + hold.providerHoldId());
      }
    }
    return Optional.empty();
  }

  void registerBoard(Board board, Map<String, UUID> holdIds) {
    boards.put(board.boardId(), new BoardState(holdIds, new HashSet<>(board.availableAngles())));
  }

  Optional<String> checkRoute(Route route) {
    routesStarted = true;
    BoardState board = boards.get(route.boardId());
    if (board == null) {
      return Optional.of("UNKNOWN_BOARD " + route.boardId());
    }
    if (routeKeys.contains(routeKey(route))) {
      return Optional.of("DUPLICATE_ROUTE");
    }
    return checkPlacements(route, board).or(() -> checkVariants(route, board));
  }

  void registerRoute(Route route) {
    routeKeys.add(routeKey(route));
  }

  BoardState board(String boardId) {
    return boards.get(boardId);
  }

  private static Optional<String> checkPlacements(Route route, BoardState board) {
    Set<String> seen = new HashSet<>();
    for (Placement placement : route.placements()) {
      if (!board.holdIds().containsKey(placement.providerHoldId())) {
        return Optional.of("UNKNOWN_HOLD " + placement.providerHoldId());
      }
      if (!seen.add(placement.providerHoldId() + "/" + placement.role())) {
        return Optional.of(
            "DUPLICATE_PLACEMENT " + placement.providerHoldId() + " " + placement.role());
      }
    }
    return Optional.empty();
  }

  private static Optional<String> checkVariants(Route route, BoardState board) {
    Set<Integer> seen = new HashSet<>();
    for (Variant variant : route.variants()) {
      if (!seen.add(variant.angleDeg())) {
        return Optional.of("DUPLICATE_ANGLE " + variant.angleDeg());
      }
      if (!board.angles().contains(variant.angleDeg())) {
        return Optional.of("ANGLE_NOT_AVAILABLE " + variant.angleDeg());
      }
      if (variant.gradeNumeric() == null && variant.gradeObservationCount() > 0) {
        return Optional.of("UNGRADED_WITH_OBSERVATIONS angle " + variant.angleDeg());
      }
      if (exceeds(variant.gradeNumeric(), GRADE_LIMIT)) {
        return Optional.of("GRADE_OUT_OF_RANGE angle " + variant.angleDeg());
      }
    }
    return Optional.empty();
  }

  private static boolean exceeds(BigDecimal value, BigDecimal limit) {
    return value != null && value.abs().compareTo(limit) >= 0;
  }

  private static String routeKey(Route route) {
    return route.boardId() + "\u0000" + route.providerRouteUuid();
  }
}
