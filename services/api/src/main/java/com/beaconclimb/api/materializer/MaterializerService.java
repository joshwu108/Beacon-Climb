package com.beaconclimb.api.materializer;

import com.beaconclimb.api.features.FeatureSnapshot;
import com.beaconclimb.api.features.FeatureSnapshotRepository;
import com.beaconclimb.api.features.StreamSnapshot;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The idempotent write of one climber-features.v1 record into the STREAM row of {@code
 * user_feature_snapshots} (BC-0305, ADR-0004 "Processing rules"). One Postgres transaction: lock
 * the stored STREAM row of the user, decide from the Kafka position, write or not.
 *
 * <p>Guard, in this order:
 *
 * <ol>
 *   <li>no stored row: apply;
 *   <li>stored row from a different partition: {@link Outcome#PARTITION_MISMATCH}. A user's records
 *       all share one key and therefore one partition; a different partition means the partition
 *       count changed, which needs a controlled reset/rebuild (ADR-0004), so the row is left alone
 *       and the record goes to the DLQ;
 *   <li>incoming offset greater than the stored one: apply. This includes a correction emitted at
 *       an unchanged {@code source_watermark} and a re-emitted identical snapshot after a stream
 *       restart; the result is the same row either way. There is deliberately no watermark guard;
 *   <li>equal offset: same checksum is {@link Outcome#DUPLICATE} (a redelivery), a different
 *       checksum is {@link Outcome#CHECKSUM_CONFLICT} (the DLQ case, row unchanged);
 *   <li>lower offset: {@link Outcome#STALE}, row unchanged.
 * </ol>
 *
 * The caller acknowledges the Kafka record only after this method has returned, so a crash between
 * the commit and the acknowledgement redelivers the record and lands in case 4 (at-least-once, no
 * divergence).
 */
public class MaterializerService {

  /** What the guard decided for one record. */
  public enum Outcome {
    APPLIED,
    DUPLICATE,
    STALE,
    CHECKSUM_CONFLICT,
    PARTITION_MISMATCH;

    /** Conflicts leave the row alone and send the record to the DLQ. */
    public boolean isConflict() {
      return this == CHECKSUM_CONFLICT || this == PARTITION_MISMATCH;
    }
  }

  /** The outcome plus a human-readable detail naming the stored and incoming positions. */
  public record Result(Outcome outcome, String detail) {}

  private final FeatureSnapshotRepository snapshots;
  private final TransactionTemplate tx;
  private final Clock clock;

  public MaterializerService(
      FeatureSnapshotRepository snapshots, TransactionTemplate tx, Clock clock) {
    this.snapshots = snapshots;
    this.tx = tx;
    this.clock = clock;
  }

  /** Applies {@code event}, consumed at {@code (partition, offset)}, in one transaction. */
  public Result apply(int partition, long offset, FeatureEvent event) {
    Result result = tx.execute(status -> applyInTransaction(partition, offset, event));
    if (result == null) {
      throw new IllegalStateException("materializer transaction returned no result");
    }
    return result;
  }

  private Result applyInTransaction(int partition, long offset, FeatureEvent event) {
    Optional<FeatureSnapshot> locked =
        snapshots.lockStream(event.userId(), FeatureEvent.FEATURE_SCHEMA_VERSION);
    if (locked.isEmpty()) {
      return write(partition, offset, event, "no stored STREAM row");
    }
    FeatureSnapshot stored = locked.get();
    String positions =
        "stored partition="
            + stored.sourcePartition()
            + " offset="
            + stored.sourceOffset()
            + " checksum="
            + stored.provenanceChecksum()
            + ", incoming partition="
            + partition
            + " offset="
            + offset
            + " checksum="
            + event.provenanceChecksum();
    if (stored.sourcePartition() == null || stored.sourcePartition() != partition) {
      return new Result(
          Outcome.PARTITION_MISMATCH,
          "feature-topic partition changed for the user (" + positions + ")");
    }
    long storedOffset = stored.sourceOffset();
    if (offset > storedOffset) {
      return write(partition, offset, event, positions);
    }
    if (offset == storedOffset) {
      if (stored.provenanceChecksum().equals(event.provenanceChecksum())) {
        return new Result(Outcome.DUPLICATE, positions);
      }
      return new Result(
          Outcome.CHECKSUM_CONFLICT,
          "same offset with a different provenance_checksum (" + positions + ")");
    }
    return new Result(Outcome.STALE, positions);
  }

  private Result write(int partition, long offset, FeatureEvent event, String detail) {
    StreamSnapshot snapshot =
        new StreamSnapshot(
            event.userId(),
            event.sourceWatermark(),
            event.featureAsOf(),
            event.aggregatesJson(),
            event.provenanceChecksum(),
            partition,
            offset,
            clock.instant().truncatedTo(ChronoUnit.MILLIS));
    if (!snapshots.upsertStream(snapshot)) {
      throw new IllegalStateException(
          "STREAM row changed under the lock; not applying partition="
              + partition
              + " offset="
              + offset);
    }
    return new Result(Outcome.APPLIED, detail);
  }
}
