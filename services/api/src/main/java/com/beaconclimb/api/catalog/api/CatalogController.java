package com.beaconclimb.api.catalog.api;

import com.beaconclimb.api.catalog.CatalogService;
import com.beaconclimb.api.catalog.api.RouteQueryParser.RawQuery;
import com.beaconclimb.api.identity.AuthenticatedUser;
import com.beaconclimb.api.platform.json.Jcs;
import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code listBoards}, {@code listRoutes} and {@code getRoute} (BC-0106). All three need a bearer
 * token (the filter rejects everything else). Query parameters arrive as raw strings so a bad type
 * is a contract 422 rather than a framework 500. The route detail carries a weak ETag over the
 * canonical (RFC 8785) body and answers 304 to a matching {@code If-None-Match}.
 */
@RestController
public class CatalogController {

  private static final String WEAK_PREFIX = "W/";

  private final CatalogService catalog;
  private final JsonMapper json;

  public CatalogController(CatalogService catalog, JsonMapper json) {
    this.catalog = catalog;
    this.json = json;
  }

  @GetMapping(path = "/v1/boards", produces = MediaType.APPLICATION_JSON_VALUE)
  public BoardListResponse listBoards(AuthenticatedUser user) {
    return catalog.listBoards();
  }

  @GetMapping(path = "/v1/routes", produces = MediaType.APPLICATION_JSON_VALUE)
  public RouteListResponse listRoutes(
      AuthenticatedUser user,
      @RequestParam(name = "board_id", required = false) String boardId,
      @RequestParam(name = "angle_deg", required = false) String angleDeg,
      @RequestParam(name = "grade_min", required = false) String gradeMin,
      @RequestParam(name = "grade_max", required = false) String gradeMax,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit) {
    return catalog.listRoutes(
        RouteQueryParser.parse(new RawQuery(boardId, angleDeg, gradeMin, gradeMax, cursor, limit)));
  }

  @GetMapping(path = "/v1/routes/{route_variant_id}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> getRoute(
      AuthenticatedUser user,
      @PathVariable("route_variant_id") String routeVariantId,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    RouteVariantView view =
        catalog
            .getRoute(routeVariantId)
            .orElseThrow(
                () ->
                    new ApiException(
                        ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "No such route variant"));
    String body = json.writeValueAsString(view);
    String etag = WEAK_PREFIX + "\"" + Jcs.fingerprint(body) + "\"";
    if (matches(ifNoneMatch, etag)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
    }
    return ResponseEntity.ok().eTag(etag).contentType(MediaType.APPLICATION_JSON).body(body);
  }

  /** RFC 9110 weak comparison: {@code *} or any listed tag equal once {@code W/} is dropped. */
  private static boolean matches(String ifNoneMatch, String etag) {
    if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
      return false;
    }
    String wanted = opaque(etag);
    for (String candidate : ifNoneMatch.split(",")) {
      String trimmed = candidate.trim();
      if ("*".equals(trimmed) || opaque(trimmed).equals(wanted)) {
        return true;
      }
    }
    return false;
  }

  private static String opaque(String tag) {
    return tag.startsWith(WEAK_PREFIX) ? tag.substring(WEAK_PREFIX.length()) : tag;
  }
}
