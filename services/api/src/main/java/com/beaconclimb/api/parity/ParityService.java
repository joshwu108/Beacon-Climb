package com.beaconclimb.api.parity;

import com.beaconclimb.api.features.FeatureRecomputeService;
import com.beaconclimb.api.features.FeatureSnapshot;
import com.beaconclimb.api.features.FeatureSnapshotRepository;
import com.beaconclimb.api.features.FeatureSnapshotRepository.Both;
import com.beaconclimb.api.features.FeatureSource;
import com.beaconclimb.api.parity.ParityReport.Detail;
import com.beaconclimb.api.parity.ParityReport.Status;
import com.beaconclimb.api.platform.json.Jcs;
import com.beaconclimb.features.FeatureAggregates;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * The batch parity oracle of ADR-0004 (BC-0306; stream design note "Parity oracle (B7)"). For every
 * non-erased user with a STREAM row in {@code user_feature_snapshots} it recomputes the
 * climber-features.v1 aggregates from Postgres at {@code W = } that row's {@code cutoff_w} through
 * {@link FeatureRecomputeService#compute} (the same query and shared computer as the SYNC
 * recompute, without writing the SYNC row) and compares the canonical aggregates text ({@code
 * FeatureAggregates.toJson()} of the batch result vs {@code Jcs.canonicalize} of the stored STREAM
 * {@code aggregates_json}) and the provenance checksum. {@code feature_as_of} is reported but not
 * compared (an empty-set STREAM record carries {@code source_watermark} there while the batch
 * stores null, climber-features.v1.md section 10).
 *
 * <p>Verdicts: {@link Status#EQUAL}; {@link Status#DIFF} with the top-level keys whose values
 * differ and whether the checksum differs; {@link Status#NO_STREAM_ROW} (skipped, counted). Cross
 * check: when both rows exist with the same {@code cutoff_w} but different checksums the user is
 * {@link Status#SAME_W_MISMATCH} (it takes precedence over EQUAL / DIFF, the batch comparison is
 * still filled in). Rows written at a catalog version other than the current one can differ in the
 * grade-derived keys without a checksum difference (section 6), which the diff keys make visible.
 *
 * <p>Read-only by construction: the only statements are SELECTs ({@code users}, {@code
 * user_feature_snapshots}, the attempts join of the recompute query). Usernames are read for the
 * synthetic-only gate and never leave this class.
 */
@Service
public class ParityService {

  private static final Logger log = LoggerFactory.getLogger(ParityService.class);
  static final String SYNTHETIC_PREFIX = "synthetic_";
  static final String E2E_PREFIX = "e2e_";
  static final String DEMO_USERNAME = "demo";

  private final JdbcClient jdbc;
  private final FeatureRecomputeService features;
  private final FeatureSnapshotRepository snapshots;
  private final Clock clock;

  public ParityService(
      JdbcClient jdbc,
      FeatureRecomputeService features,
      FeatureSnapshotRepository snapshots,
      Clock clock) {
    this.jdbc = jdbc;
    this.features = features;
    this.snapshots = snapshots;
    this.clock = clock;
  }

  /** {@code id} and whether the username is a synthetic, e2e or demo account. */
  record User(UUID id, boolean synthetic) {}

  /** Every non-erased user, or the one given, in id order. */
  public ParityReport run(Optional<UUID> onlyUser, String commit) {
    List<User> users = users(onlyUser);
    List<Detail> details = new ArrayList<>(users.size());
    int nonSynthetic = 0;
    for (User user : users) {
      Detail detail = check(user.id());
      details.add(detail);
      if (detail.status() != Status.NO_STREAM_ROW && !user.synthetic()) {
        nonSynthetic++;
      }
    }
    ParityReport report =
        new ParityReport(
            clock.instant().truncatedTo(ChronoUnit.MILLIS),
            commit,
            users.size(),
            details,
            nonSynthetic);
    log.info(
        "parity outcome=RAN users={} compared={} equal={} diffs={} same_w_mismatch={}",
        report.usersTotal(),
        report.compared(),
        report.count(Status.EQUAL),
        report.count(Status.DIFF),
        report.count(Status.SAME_W_MISMATCH));
    return report;
  }

  /** One user's verdict. */
  public Detail check(UUID userId) {
    Both rows = snapshots.findBoth(userId, FeatureAggregates.SCHEMA_VERSION);
    if (rows.stream().isEmpty()) {
      return Detail.noStreamRow(userId);
    }
    FeatureSnapshot stream = rows.stream().get();
    String streamText =
        Jcs.canonicalize(
            snapshots
                .findAggregatesText(userId, FeatureAggregates.SCHEMA_VERSION, FeatureSource.STREAM)
                .orElseThrow());
    FeatureSnapshot batch = features.compute(userId, stream.cutoffW());
    String batchText = batch.aggregates().toJson();
    List<String> diffKeys = diffKeys(Jcs.parse(batchText), Jcs.parse(streamText));
    boolean checksumDiffers = !batch.provenanceChecksum().equals(stream.provenanceChecksum());
    Status status = diffKeys.isEmpty() && !checksumDiffers ? Status.EQUAL : Status.DIFF;
    Optional<FeatureSnapshot> sync = rows.sync();
    if (sync.isPresent()
        && sync.get().cutoffW().equals(stream.cutoffW())
        && !sync.get().provenanceChecksum().equals(stream.provenanceChecksum())) {
      status = Status.SAME_W_MISMATCH;
    }
    return new Detail(
        userId,
        status,
        stream.cutoffW(),
        sync.map(FeatureSnapshot::cutoffW).orElse(null),
        stream.featureAsOf(),
        batch.featureAsOf(),
        stream.sourcePartition(),
        stream.sourceOffset(),
        diffKeys,
        checksumDiffers);
  }

  /** Top-level keys whose canonical values differ, in key order. */
  static List<String> diffKeys(JsonNode batch, JsonNode stream) {
    TreeSet<String> keys = new TreeSet<>();
    batch.propertyNames().forEach(keys::add);
    stream.propertyNames().forEach(keys::add);
    List<String> differing = new ArrayList<>();
    for (String key : keys) {
      if (!Objects.equals(batch.get(key), stream.get(key))) {
        differing.add(key);
      }
    }
    return differing;
  }

  static boolean isSynthetic(String username) {
    return username != null
        && (username.startsWith(SYNTHETIC_PREFIX)
            || username.startsWith(E2E_PREFIX)
            || username.equals(DEMO_USERNAME));
  }

  private List<User> users(Optional<UUID> onlyUser) {
    String sql = "SELECT id, username FROM users WHERE status <> 'DELETED'";
    var statement =
        onlyUser.isPresent()
            ? jdbc.sql(sql + " AND id = :id ORDER BY id").param("id", onlyUser.get())
            : jdbc.sql(sql + " ORDER BY id");
    return statement
        .query(
            (rs, rowNum) ->
                new User(rs.getObject("id", UUID.class), isSynthetic(rs.getString("username"))))
        .list();
  }
}
