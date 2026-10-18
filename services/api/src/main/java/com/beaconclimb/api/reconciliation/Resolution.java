package com.beaconclimb.api.reconciliation;

/**
 * How the repair job ({@code make reconcile}, BC-0309) closed a {@code reconciliation_events} row.
 *
 * <ul>
 *   <li>{@link #RECONCILED}: the SYNC snapshot of the user was recomputed at {@code W = now} and
 *       its {@code provenance_checksum} changed, so the late event (or something else the row had
 *       missed) is now in the live row.
 *   <li>{@link #ALREADY_CURRENT}: the recompute produced the checksum the SYNC row already had.
 *       This is the common case: the api's own attempts hook recomputes the SYNC row from Postgres
 *       right after the write, so a late event is only "late" for the STREAM row.
 *   <li>{@link #ERROR}: the recompute or the update failed. Recorded on the row, which stays open
 *       ({@code resolved_at} NULL) so the next run retries it; the run exits 1.
 * </ul>
 */
public enum Resolution {
  RECONCILED,
  ALREADY_CURRENT,
  ERROR;

  /** Resolutions that close the row ({@code resolved_at} set). */
  public boolean isTerminal() {
    return this != ERROR;
  }
}
