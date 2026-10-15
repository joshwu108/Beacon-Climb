package com.beaconclimb.api.recommendations;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Content-addressed store for candidate snapshots (ADR-0003 "Replay artifacts"): {@code put}
 * accepts only RFC 8785 canonical bytes and returns their lowercase SHA-256 hex, which is both the
 * key and {@code recommendation_decisions.candidate_set_digest}. The logical URI a decision record
 * cites is {@link #uri(String)}; where an implementation keeps the bytes is its own business.
 */
public interface SnapshotStore {

  Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
  String URI_PREFIX = "data/decisions/";

  /**
   * Stores the bytes and returns their digest. Idempotent for bytes already present.
   *
   * @throws IllegalArgumentException when the bytes are not canonical JSON
   * @throws SnapshotQuotaExceededException when the store would exceed its file quota (TM-25)
   */
  String put(byte[] canonicalBytes);

  Optional<byte[]> get(String digest);

  boolean exists(String digest);

  /** Removes the snapshot if present; a missing one is not an error. */
  void delete(String digest);

  /** {@code data/decisions/<first two hex>/<digest>.json}, the pattern the contract fixes. */
  static String uri(String digest) {
    requireDigest(digest);
    return URI_PREFIX + digest.substring(0, 2) + "/" + digest + ".json";
  }

  static void requireDigest(String digest) {
    if (digest == null || !DIGEST.matcher(digest).matches()) {
      throw new IllegalArgumentException("not a lowercase sha256 hex digest");
    }
  }
}
