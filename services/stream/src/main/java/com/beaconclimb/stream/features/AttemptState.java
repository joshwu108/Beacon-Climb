package com.beaconclimb.stream.features;

import com.beaconclimb.features.AttemptFeatureRow;
import com.beaconclimb.stream.AttemptEvent;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One entry of the per-user {@code MapState<attempt_id, AttemptState>}: the highest revision seen
 * for the attempt and, unless it is a tombstone, the fields the shared {@link
 * com.beaconclimb.features.FeatureComputer} needs. A tombstone (ATTEMPT_RETRACTED) keeps the
 * revision so a stale lower revision arriving later is still ignored, and contributes nothing.
 *
 * <p>Deliberately a mutable POJO with a public no-arg constructor and public fields, so Flink's
 * {@code PojoTypeInfo}/{@code PojoSerializer} handles it (checked by {@code
 * ClimberFeaturesFunction.ATTEMPT_STATE} being built with {@code Types.POJO}, which refuses
 * anything else). The POJO serializer supports state schema evolution (fields added or removed
 * between job versions), which Kryo-serialised generic types do not; the serialised form is
 * therefore stable across restores of the checkpointed state. {@code gradeNumeric} is boxed so a
 * null (ungraded or absent in the event) survives serialisation.
 */
public class AttemptState {

  public int revision;
  public boolean tombstone;
  public long occurredAtMillis;
  public String routeVariantId;
  public boolean sent;
  public Double gradeNumeric;

  public AttemptState() {}

  public AttemptState(
      int revision,
      boolean tombstone,
      long occurredAtMillis,
      String routeVariantId,
      boolean sent,
      Double gradeNumeric) {
    this.revision = revision;
    this.tombstone = tombstone;
    this.occurredAtMillis = occurredAtMillis;
    this.routeVariantId = routeVariantId;
    this.sent = sent;
    this.gradeNumeric = gradeNumeric;
  }

  /** The state an applied event leaves behind; a retraction becomes a tombstone. */
  public static AttemptState of(AttemptEvent event) {
    boolean retracted =
        AttemptEvent.ATTEMPT_RETRACTED.equals(event.eventType())
            || AttemptEvent.STATUS_RETRACTED.equals(event.status());
    return new AttemptState(
        event.attemptRevision(),
        retracted,
        event.occurredAtMillis(),
        event.routeVariantId(),
        event.sent(),
        event.gradeNumeric());
  }

  /** The computer's input row for this (non-tombstone) entry. */
  public AttemptFeatureRow toRow(UUID attemptId) {
    return new AttemptFeatureRow(
        attemptId,
        revision,
        routeVariantId,
        Instant.ofEpochMilli(occurredAtMillis),
        sent,
        gradeNumeric);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof AttemptState other
        && revision == other.revision
        && tombstone == other.tombstone
        && occurredAtMillis == other.occurredAtMillis
        && Objects.equals(routeVariantId, other.routeVariantId)
        && sent == other.sent
        && Objects.equals(gradeNumeric, other.gradeNumeric);
  }

  @Override
  public int hashCode() {
    return Objects.hash(revision, tombstone, occurredAtMillis, routeVariantId, sent, gradeNumeric);
  }

  @Override
  public String toString() {
    return "AttemptState[revision="
        + revision
        + ", tombstone="
        + tombstone
        + ", occurred_at_millis="
        + occurredAtMillis
        + "]";
  }
}
