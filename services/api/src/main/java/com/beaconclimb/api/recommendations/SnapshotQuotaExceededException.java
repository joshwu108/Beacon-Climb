package com.beaconclimb.api.recommendations;

/** The snapshot store holds {@code beacon.decisions.max-files} files already (TM-25). */
public class SnapshotQuotaExceededException extends RuntimeException {

  public SnapshotQuotaExceededException(long maxFiles) {
    super("snapshot store quota of " + maxFiles + " files reached");
  }
}
