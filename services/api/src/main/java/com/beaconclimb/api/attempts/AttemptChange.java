package com.beaconclimb.api.attempts;

/**
 * Kind of change behind one revision: the {@code attempt_revisions.change_type} value and the
 * {@code attempts.v1} event type it emits (contracts/events/attempts.v1.schema.json enum).
 */
public enum AttemptChange {
  RECORDED("ATTEMPT_RECORDED"),
  CORRECTED("ATTEMPT_CORRECTED"),
  RETRACTED("ATTEMPT_RETRACTED");

  private final String eventType;

  AttemptChange(String eventType) {
    this.eventType = eventType;
  }

  public String eventType() {
    return eventType;
  }
}
