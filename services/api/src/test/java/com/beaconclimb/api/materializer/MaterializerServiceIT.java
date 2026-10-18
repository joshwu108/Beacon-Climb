package com.beaconclimb.api.materializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beaconclimb.api.features.FeatureRecomputeService;
import com.beaconclimb.api.features.FeatureSnapshot;
import com.beaconclimb.api.features.FeatureSnapshotRepository;
import com.beaconclimb.api.features.FeatureSource;
import com.beaconclimb.api.materializer.MaterializerService.Outcome;
import com.beaconclimb.api.materializer.MaterializerService.Result;
import com.beaconclimb.api.platform.json.Jcs;
import com.beaconclimb.features.FeatureAggregates;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * BC-0305 guard rules through {@link MaterializerService} directly, where the Kafka position is a
 * parameter and so can be replayed, reordered and faked at will: redelivery of one position,
 * same-watermark correction at a new offset, an older offset, an equal offset with a different
 * checksum, a partition change, the stored aggregate text, and coexistence with the SYNC row.
 */
class MaterializerServiceIT extends MaterializerTestSupport {

  @Autowired FeatureRecomputeService features;

  @Test
  @DisplayName("(a) the same (partition, offset) applied 10x -> one STREAM row, nine duplicates")
  void redeliveryOfOnePositionIsIdempotent() {
    Account account = registerWithConsent();
    FeatureEvent event = event(featureRecord(account.userId(), CHECKSUM_A, aggregates(1)));
    double appliedBefore = counter(MaterializerMetrics.APPLIED);

    Result first = materializer.apply(2, 10L, event);
    for (int i = 0; i < 9; i++) {
      assertThat(materializer.apply(2, 10L, event).outcome()).isEqualTo(Outcome.DUPLICATE);
    }

    assertThat(first.outcome()).isEqualTo(Outcome.APPLIED);
    assertThat(streamRows(account.userId())).isEqualTo(1);
    FeatureSnapshot row = streamRow(account.userId()).orElseThrow();
    assertThat(row.provenanceChecksum()).isEqualTo(CHECKSUM_A);
    assertThat(row.source()).isEqualTo(FeatureSource.STREAM);
    assertThat(row.sourcePartition()).isEqualTo(2);
    assertThat(row.sourceOffset()).isEqualTo(10L);
    assertThat(row.cutoffW()).isEqualTo(W);
    assertThat(row.featureAsOf()).isEqualTo(AS_OF);
    // The service decides; the listener counts. Direct calls leave the counters alone.
    assertThat(counter(MaterializerMetrics.APPLIED)).isEqualTo(appliedBefore);
  }

  @Test
  @DisplayName("(b) a correction at an unchanged source_watermark but a higher offset is applied")
  void sameWatermarkCorrectionAtNewOffsetIsApplied() {
    Account account = registerWithConsent();
    materializer.apply(1, 5L, event(featureRecord(account.userId(), CHECKSUM_A, aggregates(1))));

    Result corrected =
        materializer.apply(
            1, 6L, event(featureRecord(account.userId(), CHECKSUM_B, aggregates(2))));

    assertThat(corrected.outcome()).isEqualTo(Outcome.APPLIED);
    FeatureSnapshot row = streamRow(account.userId()).orElseThrow();
    assertThat(row.cutoffW()).isEqualTo(W);
    assertThat(row.provenanceChecksum()).isEqualTo(CHECKSUM_B);
    assertThat(row.aggregates()).isEqualTo(aggregates(2));
    assertThat(row.sourceOffset()).isEqualTo(6L);
    assertThat(streamRows(account.userId())).isEqualTo(1);
  }

  @Test
  @DisplayName("(c) an older offset is ignored even with a newer watermark")
  void olderOffsetIsStale() {
    Account account = registerWithConsent();
    byte[] newer = featureRecord(account.userId(), CHECKSUM_A, aggregates(1));
    byte[] older =
        featureRecord(
            account.userId(),
            W.plus(Duration.ofHours(1)),
            AS_OF,
            CHECKSUM_B,
            aggregates(2).toJson());
    materializer.apply(3, 8L, event(newer));
    FeatureSnapshot before = streamRow(account.userId()).orElseThrow();

    Result stale = materializer.apply(3, 7L, event(older));

    assertThat(stale.outcome()).isEqualTo(Outcome.STALE);
    assertThat(streamRow(account.userId())).contains(before);
  }

  @Test
  @DisplayName("(d) an equal offset with a different checksum is a conflict and changes nothing")
  void equalOffsetDifferentChecksumIsConflict() {
    Account account = registerWithConsent();
    materializer.apply(4, 20L, event(featureRecord(account.userId(), CHECKSUM_A, aggregates(1))));
    FeatureSnapshot before = streamRow(account.userId()).orElseThrow();

    Result conflict =
        materializer.apply(
            4, 20L, event(featureRecord(account.userId(), CHECKSUM_B, aggregates(2))));

    assertThat(conflict.outcome()).isEqualTo(Outcome.CHECKSUM_CONFLICT);
    assertThat(conflict.outcome().isConflict()).isTrue();
    assertThat(conflict.detail()).contains("offset=20").contains(CHECKSUM_A).contains(CHECKSUM_B);
    assertThat(streamRow(account.userId())).contains(before);
  }

  @Test
  @DisplayName("a record from another partition for the same user is a conflict, not an apply")
  void partitionChangeIsConflict() {
    Account account = registerWithConsent();
    materializer.apply(0, 3L, event(featureRecord(account.userId(), CHECKSUM_A, aggregates(1))));
    FeatureSnapshot before = streamRow(account.userId()).orElseThrow();

    Result mismatch =
        materializer.apply(
            5, 99L, event(featureRecord(account.userId(), CHECKSUM_B, aggregates(2))));

    assertThat(mismatch.outcome()).isEqualTo(Outcome.PARTITION_MISMATCH);
    assertThat(mismatch.detail()).contains("partition=0").contains("partition=5");
    assertThat(streamRow(account.userId())).contains(before);
  }

  @Test
  @DisplayName(
      "aggregates_json holds the received canonical text (jsonb re-normalises, not the api)")
  void storedAggregatesAreTheReceivedCanonicalText() {
    Account account = registerWithConsent();
    FeatureAggregates aggregates = aggregates(1);
    byte[] bytes = featureRecord(account.userId(), CHECKSUM_A, aggregates);
    FeatureEvent event = event(bytes);
    assertThat(event.aggregatesJson()).isEqualTo(aggregates.toJson());
    assertThat(event.aggregatesJson()).isEqualTo(Jcs.canonicalize(event.aggregatesJson()));

    materializer.apply(1, 1L, event);

    assertThat(Jcs.canonicalize(storedStreamAggregatesText(account.userId())))
        .isEqualTo(aggregates.toJson());
    assertThat(streamRow(account.userId()).orElseThrow().aggregates()).isEqualTo(aggregates);
  }

  @Test
  @DisplayName("(g) SYNC and STREAM rows coexist; each writer leaves the other's row untouched")
  void syncAndStreamRowsCoexist() {
    Account account = registerWithConsent();
    createAttempt(account, visibleRouteId());
    FeatureSnapshot sync = syncRow(account.userId()).orElseThrow();
    assertThat(sync.source()).isEqualTo(FeatureSource.SYNC);
    assertThat(sync.sourcePartition()).isNull();
    assertThat(streamRow(account.userId())).isEmpty();

    materializer.apply(2, 1L, event(featureRecord(account.userId(), CHECKSUM_A, aggregates(1))));

    assertThat(syncRow(account.userId())).contains(sync);
    FeatureSnapshot stream = streamRow(account.userId()).orElseThrow();
    assertThat(stream.source()).isEqualTo(FeatureSource.STREAM);
    FeatureSnapshotRepository.Both both =
        snapshots.findBoth(account.userId(), FeatureAggregates.SCHEMA_VERSION);
    assertThat(both.sync()).contains(sync);
    assertThat(both.stream()).contains(stream);

    Instant later = sync.cutoffW().plus(Duration.ofMinutes(5));
    FeatureRecomputeService.Outcome recomputed = features.recompute(account.userId(), later);
    assertThat(recomputed.stored()).isTrue();
    assertThat(syncRow(account.userId()).orElseThrow().cutoffW()).isEqualTo(later);
    assertThat(streamRow(account.userId())).contains(stream);

    materializer.apply(2, 2L, event(featureRecord(account.userId(), CHECKSUM_B, aggregates(2))));
    assertThat(syncRow(account.userId()).orElseThrow().cutoffW()).isEqualTo(later);
    assertThat(streamRow(account.userId()).orElseThrow().provenanceChecksum())
        .isEqualTo(CHECKSUM_B);
    assertThat(
            jdbc.sql("SELECT count(*) FROM user_feature_snapshots WHERE user_id = :id")
                .param("id", account.userId())
                .query(Integer.class)
                .single())
        .isEqualTo(2);
  }

  @Test
  @DisplayName("the SYNC upsert refuses a STREAM snapshot")
  void syncUpsertRejectsStreamSource() {
    FeatureSnapshot stream =
        new FeatureSnapshot(
            UUID.randomUUID(),
            FeatureAggregates.SCHEMA_VERSION,
            W,
            AS_OF,
            aggregates(1),
            CHECKSUM_A,
            FeatureSource.STREAM,
            0,
            0L,
            W);
    assertThatThrownBy(() -> snapshots.upsert(stream))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("upsertStream");
  }
}
