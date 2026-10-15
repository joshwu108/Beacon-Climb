package com.beaconclimb.api.catalog.importer;

import com.beaconclimb.api.catalog.importer.CatalogRecords.Placement;
import com.beaconclimb.api.catalog.importer.CatalogRecords.Route;
import com.beaconclimb.api.catalog.importer.CatalogRecords.Variant;
import com.beaconclimb.api.catalog.importer.CatalogRepository.PlacementRows;
import com.beaconclimb.api.catalog.importer.CatalogRepository.RouteRows;
import com.beaconclimb.api.catalog.importer.CatalogRepository.Upsert;
import com.beaconclimb.api.catalog.importer.CatalogRepository.VariantRows;
import com.beaconclimb.api.catalog.importer.CatalogValidator.BoardState;
import com.beaconclimb.api.catalog.importer.ImportResult.RowCounts;
import com.beaconclimb.api.identity.Uuid7;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Buffers accepted routes and writes them in chunks: routes, then their variants, then the
 * placements (deleted and re-inserted per variant; a route's placements are copied onto every
 * variant, planning/06 section 1). Counts inserts and updates separately.
 */
final class RouteWriter {

  private final CatalogRepository repository;
  private final CatalogValidator validator;
  private final UUID provenanceId;
  private final int chunkSize;
  private final List<Route> buffer = new ArrayList<>();
  private RowCounts inserted = RowCounts.ZERO;
  private RowCounts updated = RowCounts.ZERO;

  RouteWriter(
      CatalogRepository repository, CatalogValidator validator, UUID provenanceId, int chunkSize) {
    this.repository = repository;
    this.validator = validator;
    this.provenanceId = provenanceId;
    this.chunkSize = chunkSize;
  }

  void add(Route route) {
    buffer.add(route);
    if (buffer.size() >= chunkSize) {
      flush();
    }
  }

  void flush() {
    if (buffer.isEmpty()) {
      return;
    }
    Map<String, Upsert> routes = repository.upsertRoutes(routeRows(buffer), provenanceId);
    tally(routes.values(), 1, 0);
    Map<String, Upsert> variants = repository.upsertVariants(variantRows(buffer, routes));
    tally(variants.values(), 0, 1);
    String[] variantIds =
        variants.values().stream().map(u -> u.id().toString()).toArray(String[]::new);
    repository.deletePlacements(variantIds);
    int placements = repository.insertPlacements(placementRows(buffer, routes, variants));
    inserted = inserted.plus(new RowCounts(0, 0, 0, 0, placements));
    buffer.clear();
  }

  RowCounts inserted() {
    return inserted;
  }

  RowCounts updated() {
    return updated;
  }

  private void tally(Iterable<Upsert> outcomes, int routeWeight, int variantWeight) {
    int ins = 0;
    int upd = 0;
    for (Upsert outcome : outcomes) {
      if (outcome.inserted()) {
        ins++;
      } else {
        upd++;
      }
    }
    inserted = inserted.plus(new RowCounts(0, 0, ins * routeWeight, ins * variantWeight, 0));
    updated = updated.plus(new RowCounts(0, 0, upd * routeWeight, upd * variantWeight, 0));
  }

  private static String routeKey(Route route) {
    return route.boardId() + "\u0000" + route.providerRouteUuid();
  }

  private static RouteRows routeRows(List<Route> routes) {
    int n = routes.size();
    RouteRows rows =
        new RouteRows(
            new String[n],
            new String[n],
            new String[n],
            new String[n],
            new String[n],
            new String[n],
            new String[n],
            new String[n]);
    for (int i = 0; i < n; i++) {
      Route route = routes.get(i);
      rows.ids()[i] = Uuid7.next().toString();
      rows.boardIds()[i] = route.boardId();
      rows.uuids()[i] = route.providerRouteUuid();
      rows.names()[i] = route.name();
      rows.setters()[i] = route.setterPseudonym();
      rows.tagsJson()[i] = CatalogRecords.MAPPER.writeValueAsString(route.tags());
      rows.activeFrom()[i] = route.activeFrom();
      rows.activeTo()[i] = route.activeTo();
    }
    return rows;
  }

  private static VariantRows variantRows(List<Route> routes, Map<String, Upsert> routeIds) {
    List<String[]> cols = new ArrayList<>();
    int n = routes.stream().mapToInt(r -> r.variants().size()).sum();
    for (int c = 0; c < 6; c++) {
      cols.add(new String[n]);
    }
    int i = 0;
    for (Route route : routes) {
      String routeId = routeIds.get(routeKey(route)).id().toString();
      for (Variant variant : route.variants()) {
        cols.get(0)[i] = Uuid7.next().toString();
        cols.get(1)[i] = routeId;
        cols.get(2)[i] = Integer.toString(variant.angleDeg());
        cols.get(3)[i] =
            variant.gradeNumeric() == null ? null : variant.gradeNumeric().toPlainString();
        cols.get(4)[i] = Integer.toString(variant.gradeObservationCount());
        cols.get(5)[i] = variant.statusOrDefault();
        i++;
      }
    }
    return new VariantRows(
        cols.get(0), cols.get(1), cols.get(2), cols.get(3), cols.get(4), cols.get(5));
  }

  private PlacementRows placementRows(
      List<Route> routes, Map<String, Upsert> routeIds, Map<String, Upsert> variantIds) {
    List<String> vids = new ArrayList<>();
    List<String> hids = new ArrayList<>();
    List<String> roles = new ArrayList<>();
    List<String> orders = new ArrayList<>();
    for (Route route : routes) {
      BoardState board = validator.board(route.boardId());
      String routeId = routeIds.get(routeKey(route)).id().toString();
      for (Variant variant : route.variants()) {
        String variantId = variantIds.get(routeId + ":" + variant.angleDeg()).id().toString();
        for (Placement placement : route.placements()) {
          vids.add(variantId);
          hids.add(board.holdIds().get(placement.providerHoldId()).toString());
          roles.add(placement.role());
          orders.add(
              placement.placementOrder() == null ? null : placement.placementOrder().toString());
        }
      }
    }
    return new PlacementRows(
        vids.toArray(String[]::new),
        hids.toArray(String[]::new),
        roles.toArray(String[]::new),
        orders.toArray(String[]::new));
  }
}
