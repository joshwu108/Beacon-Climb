package com.beaconclimb.events;

/**
 * Why a record was rejected; the {@code error_code} of the stream-dlq.v1 record it becomes
 * (contracts/events/stream-dlq.v1.schema.json) and the {@code dlq.error_code} header. The names are
 * part of the operator contract (docs/runbooks/dlq.md), so a code is never renamed.
 */
public enum ErrorCode {
  /** The bytes are not one well-formed JSON document (parse error, duplicates, trailing data). */
  NOT_JSON,
  /** The document conforms to nothing: a member is missing, mistyped, out of range or unknown. */
  SCHEMA_VIOLATION,
  /** {@code attributes} carries a key outside the closed attempts.v1 allowlist (C-01, TM-11). */
  UNKNOWN_ATTRIBUTE,
  /** A member named {@code notes} appears at any depth: free text never enters the stream. */
  NOTES_PRESENT,
  /** No schema is registered for the topic the record came from. */
  UNKNOWN_TOPIC,
  /**
   * Reserved for the materializer (BC-0305, ADR-0004): the same feature-topic offset arrived with a
   * different {@code provenance_checksum} than the stored snapshot.
   */
  CHECKSUM_CONFLICT
}
