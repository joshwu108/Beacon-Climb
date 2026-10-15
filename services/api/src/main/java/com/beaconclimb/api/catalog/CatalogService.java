package com.beaconclimb.api.catalog;

import com.beaconclimb.api.catalog.CatalogQueryRepository.BoardRow;
import com.beaconclimb.api.catalog.CatalogQueryRepository.HoldRow;
import com.beaconclimb.api.catalog.CatalogQueryRepository.PlacementRow;
import com.beaconclimb.api.catalog.CatalogQueryRepository.VariantRow;
import com.beaconclimb.api.catalog.api.BoardListResponse;
import com.beaconclimb.api.catalog.api.BoardView;
import com.beaconclimb.api.catalog.api.PlacementView;
import com.beaconclimb.api.catalog.api.RouteListResponse;
import com.beaconclimb.api.catalog.api.RouteVariantSummaryView;
import com.beaconclimb.api.catalog.api.RouteVariantView;
import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.FieldError;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Catalog read use cases (BC-0106): boards, the filtered keyset route list and one route variant.
 * Visibility (ACTIVE and inside the route's activity window) is decided in SQL, evaluated at the
 * injected clock's "now". {@code grade_label} is always null: the label-to-grade_numeric table
 * (contracts/catalog/grade-scale.v1.json) is BLOCKED, so clients filter on {@code grade_numeric}.
 */
@Service
public class CatalogService {

  private static final TypeReference<List<Integer>> INT_LIST = new TypeReference<>() {};
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

  private final CatalogQueryRepository repository;
  private final Clock clock;
  private final JsonMapper json;

  public CatalogService(CatalogQueryRepository repository, Clock clock, JsonMapper json) {
    this.repository = repository;
    this.clock = clock;
    this.json = json;
  }

  public BoardListResponse listBoards() {
    return new BoardListResponse(repository.listBoards().stream().map(this::board).toList());
  }

  /** Throws 422 UNKNOWN_BOARD for a board id that does not exist; an absent board id means all. */
  public RouteListResponse listRoutes(RouteQuery query) {
    if (query.boardId() != null && !repository.boardExists(query.boardId())) {
      throw new ApiException(
          ErrorCode.UNKNOWN_BOARD,
          HttpStatus.UNPROCESSABLE_CONTENT,
          "Unknown board",
          List.of(new FieldError("board_id", "unknown", "no such board")));
    }
    List<VariantRow> rows = repository.listRoutes(query, clock.instant());
    boolean hasMore = rows.size() > query.limit();
    List<VariantRow> page = hasMore ? rows.subList(0, query.limit()) : rows;
    VariantRow last = page.isEmpty() ? null : page.get(page.size() - 1);
    String next = hasMore ? new RouteCursor(last.gradeNumeric(), last.variantId()).encode() : null;
    return new RouteListResponse(page.stream().map(CatalogService::summary).toList(), next);
  }

  /** Empty for a malformed id, an unknown variant or one that is not visible: all are 404. */
  public Optional<RouteVariantView> getRoute(String publicId) {
    return RouteVariantId.parse(publicId)
        .flatMap(id -> repository.findVisibleVariant(id, clock.instant()))
        .map(this::detail);
  }

  private RouteVariantView detail(VariantRow row) {
    List<PlacementView> placements =
        repository.placements(row.variantId()).stream().map(CatalogService::placement).toList();
    List<String> tags = json.readValue(row.tagsJson(), STRING_LIST);
    return new RouteVariantView(
        RouteVariantId.format(row.providerRouteUuid(), row.angleDeg()),
        row.boardId(),
        row.angleDeg(),
        nameOf(row),
        number(row.gradeNumeric()),
        null,
        row.gradeObservationCount(),
        placements,
        tags,
        tags.isEmpty());
  }

  private static RouteVariantSummaryView summary(VariantRow row) {
    return new RouteVariantSummaryView(
        RouteVariantId.format(row.providerRouteUuid(), row.angleDeg()),
        row.boardId(),
        row.angleDeg(),
        nameOf(row),
        number(row.gradeNumeric()),
        null,
        row.gradeObservationCount());
  }

  /** The contract requires a string name; an unnamed route shows its provider id instead. */
  private static String nameOf(VariantRow row) {
    return row.name() == null ? row.providerRouteUuid() : row.name();
  }

  private static PlacementView placement(PlacementRow row) {
    return new PlacementView(
        row.holdId(), row.x().doubleValue(), row.y().doubleValue(), row.role());
  }

  private BoardView board(BoardRow row) {
    List<BoardView.Hold> holds = row.holds().stream().map(CatalogService::hold).toList();
    return new BoardView(
        row.boardId(),
        row.provider(),
        row.layoutId(),
        row.sizeId(),
        row.synthetic(),
        holds,
        json.readValue(row.geometryJson(), BoardView.Geometry.class),
        json.readValue(row.availableAnglesJson(), INT_LIST));
  }

  private static BoardView.Hold hold(HoldRow row) {
    return new BoardView.Hold(row.holdId(), row.x().doubleValue(), row.y().doubleValue());
  }

  /** numeric(5,2) renders as a JSON number such as 17.5, never as a string. */
  private static Double number(BigDecimal value) {
    return value == null ? null : value.doubleValue();
  }
}
