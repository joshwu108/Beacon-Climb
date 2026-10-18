package com.beaconclimb.stream;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;

/**
 * The fields of an {@code attempts.v1} event the job needs
 * (contracts/events/attempts.v1.schema.json) plus the original bytes, which are forwarded unchanged
 * to the reconciliation and DLQ topics. {@code gradeNumeric} is the OPTIONAL {@code
 * attributes.grade_numeric} added consumer-first by BC-0301/BC-0302: {@code null} when absent
 * (treated as ungraded downstream).
 */
public record AttemptEvent(
    String eventId,
    String eventType,
    String userId,
    String attemptId,
    int attemptRevision,
    String routeVariantId,
    long occurredAtMillis,
    boolean sent,
    String status,
    Double gradeNumeric,
    byte[] raw)
    implements Serializable {

  public AttemptEvent {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(attemptId, "attemptId");
    Objects.requireNonNull(raw, "raw");
    raw = raw.clone();
  }

  @Override
  public byte[] raw() {
    return raw.clone();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof AttemptEvent other
        && eventId.equals(other.eventId)
        && eventType.equals(other.eventType)
        && userId.equals(other.userId)
        && attemptId.equals(other.attemptId)
        && attemptRevision == other.attemptRevision
        && routeVariantId.equals(other.routeVariantId)
        && occurredAtMillis == other.occurredAtMillis
        && sent == other.sent
        && status.equals(other.status)
        && Objects.equals(gradeNumeric, other.gradeNumeric)
        && Arrays.equals(raw, other.raw);
  }

  @Override
  public int hashCode() {
    return Objects.hash(eventId, attemptId, attemptRevision);
  }

  @Override
  public String toString() {
    return "AttemptEvent[event_id="
        + eventId
        + ", event_type="
        + eventType
        + ", user_id="
        + userId
        + ", attempt_id="
        + attemptId
        + ", attempt_revision="
        + attemptRevision
        + ", occurred_at_millis="
        + occurredAtMillis
        + "]";
  }
}
