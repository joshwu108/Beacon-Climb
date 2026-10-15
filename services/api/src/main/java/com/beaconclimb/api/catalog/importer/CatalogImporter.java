package com.beaconclimb.api.catalog.importer;

import com.beaconclimb.api.catalog.importer.CatalogLineReader.Line;
import com.beaconclimb.api.catalog.importer.CatalogRecords.Board;
import com.beaconclimb.api.catalog.importer.CatalogRecords.Hold;
import com.beaconclimb.api.catalog.importer.CatalogRecords.Manifest;
import com.beaconclimb.api.catalog.importer.CatalogRecords.Route;
import com.beaconclimb.api.catalog.importer.CatalogRepository.Upsert;
import com.beaconclimb.api.catalog.importer.ImportRefusedException.Reason;
import com.beaconclimb.api.catalog.importer.ImportResult.RowCounts;
import com.beaconclimb.api.identity.Uuid7;
import com.beaconclimb.api.platform.json.Jcs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Loads a catalog v1 JSON Lines file (BC-0105). Order of checks: manifest on line 1 and valid,
 * licence gate, content_sha256 over the canonicalised record lines, record counts; only then is
 * anything written, all of it in one transaction. Malformed record lines are reported and skipped;
 * the accepted rows of such a file are still committed and the caller exits 2. Logs carry counts
 * only, never row contents.
 */
@Service
public class CatalogImporter {

  private static final Logger log = LoggerFactory.getLogger(CatalogImporter.class);
  static final int CHUNK_SIZE = 500;
  private static final Set<String> AUDITABLE_LICENSES =
      Set.of("LOCAL_RESEARCH_ONLY", "REVIEWED_PERMITTED");

  private final CatalogSchema schema;
  private final CatalogRepository repository;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public CatalogImporter(
      CatalogSchema schema,
      CatalogRepository repository,
      TransactionTemplate transaction,
      Clock clock) {
    this.schema = schema;
    this.repository = repository;
    this.transaction = transaction;
    this.clock = clock;
  }

  /** Imports {@code file}; rejected lines go to a report under {@code reportDir}. */
  public ImportResult importFile(Path file, boolean acknowledgeAudit, Path reportDir) {
    if (!Files.isRegularFile(file)) {
      throw new ImportRefusedException(Reason.FILE_INVALID, "not a file: " + file);
    }
    try {
      Manifest manifest = readManifest(file);
      gate(manifest, acknowledgeAudit);
      verifyContent(file, manifest);
      log.info(
          "catalog file verified: source={} version={}", manifest.source(), manifest.version());
      ImportResult result = transaction.execute(status -> persist(file, manifest, reportDir));
      log.info(
          "catalog import done: inserted={} updated={} rejected={}",
          result.inserted(),
          result.updated(),
          result.rejected());
      return result;
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
  }

  private Manifest readManifest(Path file) throws IOException {
    try (CatalogLineReader reader = new CatalogLineReader(file)) {
      Line first = reader.next();
      if (first == null) {
        throw new ImportRefusedException(Reason.FILE_INVALID, "EMPTY_FILE");
      }
      JsonNode node = parseStrict(first);
      if (!schema.recordType(node).filter(CatalogRecords.TYPE_MANIFEST::equals).isPresent()) {
        throw new ImportRefusedException(
            Reason.FILE_INVALID, "MANIFEST_FIRST line 1 is not a manifest");
      }
      Optional<String> error = schema.validate(CatalogRecords.TYPE_MANIFEST, node);
      if (error.isPresent()) {
        throw new ImportRefusedException(Reason.FILE_INVALID, "manifest " + error.get());
      }
      return CatalogRecords.manifest(node);
    }
  }

  /** planning/06 section 2: synthetic=false needs an auditable status and an explicit ack. */
  static void gate(Manifest manifest, boolean acknowledgeAudit) {
    if (manifest.synthetic()) {
      return;
    }
    if (!AUDITABLE_LICENSES.contains(manifest.licenseReviewStatus())) {
      throw new ImportRefusedException(
          Reason.LICENSE_GATE,
          "synthetic=false with license_review_status=" + manifest.licenseReviewStatus());
    }
    if (!acknowledgeAudit) {
      throw new ImportRefusedException(
          Reason.LICENSE_GATE,
          "synthetic=false ("
              + manifest.licenseReviewStatus()
              + ") requires --import.acknowledge-audit=true after the data-source audit");
    }
  }

  /** Streams lines 2..n through the RFC 8785 digest and counts records; nothing is stored. */
  private void verifyContent(Path file, Manifest manifest) throws IOException {
    MessageDigest digest = Jcs.sha256();
    Map<String, Integer> counts = new HashMap<>();
    int lines = 0;
    try (CatalogLineReader reader = new CatalogLineReader(file)) {
      reader.next();
      for (Line line = reader.next(); line != null; line = reader.next()) {
        if (lines++ > 0) {
          digest.update((byte) '\n');
        }
        JsonNode node = parseStrict(line);
        digest.update(Jcs.canonicalize(node).getBytes(StandardCharsets.UTF_8));
        counts.merge(schema.recordType(node).orElse("?"), 1, Integer::sum);
      }
    }
    String actual = HexFormat.of().formatHex(digest.digest());
    if (!actual.equals(manifest.contentSha256())) {
      throw new ImportRefusedException(
          Reason.CONTENT_SHA256_MISMATCH,
          "CONTENT_SHA256_MISMATCH manifest " + manifest.contentSha256() + " computed " + actual);
    }
    int boards = counts.getOrDefault(CatalogRecords.TYPE_BOARD, 0);
    int routes = counts.getOrDefault(CatalogRecords.TYPE_ROUTE, 0);
    if (boards != manifest.recordCounts().boards() || routes != manifest.recordCounts().routes()) {
      throw new ImportRefusedException(
          Reason.FILE_INVALID,
          "RECORD_COUNTS_MISMATCH manifest boards="
              + manifest.recordCounts().boards()
              + " routes="
              + manifest.recordCounts().routes()
              + " file boards="
              + boards
              + " routes="
              + routes);
    }
  }

  private static JsonNode parseStrict(Line line) {
    try {
      return Jcs.parse(line.text());
    } catch (Jcs.JcsException e) {
      throw new ImportRefusedException(
          Reason.CONTENT_SHA256_MISMATCH,
          "NOT_JSON line " + line.number() + " cannot be canonicalised: " + e.getMessage());
    }
  }

  private ImportResult persist(Path file, Manifest manifest, Path reportDir) {
    Upsert provenance = repository.upsertProvenance(Uuid7.next(), manifest, clock.instant());
    CatalogValidator validator = new CatalogValidator(manifest.synthetic());
    RouteWriter routes = new RouteWriter(repository, validator, provenance.id(), CHUNK_SIZE);
    BoardWriter boards = new BoardWriter(validator, provenance.id());
    try (RejectionReport report = new RejectionReport(reportDir, clock);
        CatalogLineReader reader = new CatalogLineReader(file)) {
      reader.next();
      for (Line line = reader.next(); line != null; line = reader.next()) {
        processLine(line, Jcs.parse(line.text()), boards, routes, validator, report);
      }
      routes.flush();
      return new ImportResult(
          provenance.id(),
          provenance.inserted(),
          boards.inserted.plus(routes.inserted()),
          boards.updated.plus(routes.updated()),
          report.count(),
          report.fileIfWritten());
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
  }

  private void processLine(
      Line line,
      JsonNode node,
      BoardWriter boards,
      RouteWriter routes,
      CatalogValidator validator,
      RejectionReport report) {
    Optional<String> type = schema.recordType(node);
    if (type.isEmpty()) {
      report.reject(line.number(), "UNKNOWN_RECORD_TYPE", null, null);
      return;
    }
    if (type.get().equals(CatalogRecords.TYPE_MANIFEST)) {
      report.reject(line.number(), "DUPLICATE_MANIFEST", type.get(), null);
      return;
    }
    Optional<String> schemaError = schema.validate(type.get(), node);
    if (schemaError.isPresent()) {
      report.reject(line.number(), schemaError.get(), type.get(), idOf(type.get(), node));
      return;
    }
    if (type.get().equals(CatalogRecords.TYPE_BOARD)) {
      Board board = CatalogRecords.board(node);
      Optional<String> error = validator.checkBoard(board);
      if (error.isPresent()) {
        report.reject(line.number(), error.get(), type.get(), board.boardId());
        return;
      }
      boards.write(board);
      return;
    }
    Route route = CatalogRecords.route(node);
    Optional<String> error = validator.checkRoute(route);
    if (error.isPresent()) {
      report.reject(line.number(), error.get(), type.get(), route.providerRouteUuid());
      return;
    }
    validator.registerRoute(route);
    routes.add(route);
  }

  private static String idOf(String type, JsonNode node) {
    String field = type.equals(CatalogRecords.TYPE_BOARD) ? "board_id" : "provider_route_uuid";
    JsonNode id = node.get(field);
    return id != null && id.isString() ? id.stringValue() : null;
  }

  /** Writes a board and its holds in chunks and registers the hold ids for route placements. */
  private final class BoardWriter {
    private final CatalogValidator validator;
    private final UUID provenanceId;
    private RowCounts inserted = RowCounts.ZERO;
    private RowCounts updated = RowCounts.ZERO;

    BoardWriter(CatalogValidator validator, UUID provenanceId) {
      this.validator = validator;
      this.provenanceId = provenanceId;
    }

    void write(Board board) {
      Upsert outcome = repository.upsertBoard(board, canonicalConfig(board), provenanceId);
      Map<String, UUID> holdIds = new HashMap<>();
      int holdsInserted = 0;
      List<Hold> holds = board.holds();
      for (int from = 0; from < holds.size(); from += CHUNK_SIZE) {
        List<Hold> chunk = holds.subList(from, Math.min(holds.size(), from + CHUNK_SIZE));
        for (Map.Entry<String, Upsert> e :
            repository.upsertHolds(board.boardId(), chunk).entrySet()) {
          holdIds.put(e.getKey(), e.getValue().id());
          holdsInserted += e.getValue().inserted() ? 1 : 0;
        }
      }
      int holdsUpdated = holds.size() - holdsInserted;
      inserted = inserted.plus(new RowCounts(outcome.inserted() ? 1 : 0, holdsInserted, 0, 0, 0));
      updated = updated.plus(new RowCounts(outcome.inserted() ? 0 : 1, holdsUpdated, 0, 0, 0));
      validator.registerBoard(board, holdIds);
    }

    /** boards.canonical_config_json: the board definition without its holds, in JCS form. */
    private String canonicalConfig(Board board) {
      Map<String, Object> config = new LinkedHashMap<>();
      config.put("provider", board.provider());
      config.put("layout_id", board.layoutId());
      config.put("size_id", board.sizeId());
      config.put("geometry", board.geometry());
      config.put("available_angles", board.availableAngles());
      return Jcs.canonicalize(CatalogRecords.MAPPER.writeValueAsString(config));
    }
  }
}
