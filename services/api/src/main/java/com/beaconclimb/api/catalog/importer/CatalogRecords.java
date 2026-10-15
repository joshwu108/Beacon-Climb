package com.beaconclimb.api.catalog.importer;

import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Typed views of the three catalog v1 line records (contracts/catalog/catalog-v1.schema.json).
 * Lines are schema-validated before they are mapped here, so the mapper tolerates the {@code type}
 * discriminator and reads snake_case names.
 */
public final class CatalogRecords {

  public static final JsonMapper MAPPER =
      JsonMapper.builder()
          .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  public static final String TYPE_MANIFEST = "manifest";
  public static final String TYPE_BOARD = "board";
  public static final String TYPE_ROUTE = "route";

  private CatalogRecords() {}

  public record RecordCounts(int boards, int routes) {}

  public record Manifest(
      int schemaVersion,
      String source,
      String version,
      String contentSha256,
      String extractedAt,
      boolean synthetic,
      String licenseReviewStatus,
      RecordCounts recordCounts,
      String notes) {}

  public record Hold(String providerHoldId, BigDecimal x, BigDecimal y, JsonNode geometryMeta) {}

  public record Board(
      String boardId,
      String provider,
      String layoutId,
      String sizeId,
      boolean synthetic,
      JsonNode geometry,
      List<Integer> availableAngles,
      List<Hold> holds) {}

  public record Placement(String providerHoldId, String role, Integer placementOrder) {}

  public record Variant(
      int angleDeg, BigDecimal gradeNumeric, int gradeObservationCount, String validationStatus) {

    public String statusOrDefault() {
      return validationStatus == null ? "ACTIVE" : validationStatus;
    }
  }

  public record Route(
      String providerRouteUuid,
      String boardId,
      String name,
      String setterPseudonym,
      List<String> tags,
      String activeFrom,
      String activeTo,
      List<Placement> placements,
      List<Variant> variants) {}

  public static Manifest manifest(JsonNode node) {
    return MAPPER.treeToValue(node, Manifest.class);
  }

  public static Board board(JsonNode node) {
    return MAPPER.treeToValue(node, Board.class);
  }

  public static Route route(JsonNode node) {
    return MAPPER.treeToValue(node, Route.class);
  }
}
