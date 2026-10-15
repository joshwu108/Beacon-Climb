package com.beaconclimb.api.catalog.importer;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Outcome of one file import. {@code inserted} and {@code updated} count rows by their upsert
 * outcome ({@code xmax = 0} means the row was inserted by this transaction). Rejected lines were
 * skipped; the accepted rows are committed regardless and the process exits 2 to flag the report.
 */
public record ImportResult(
    UUID provenanceId,
    boolean provenanceInserted,
    RowCounts inserted,
    RowCounts updated,
    int rejected,
    Path reportFile) {

  public static final int EXIT_OK = 0;
  public static final int EXIT_REJECTED_ROWS = 2;

  /** Row counts per catalog table. */
  public record RowCounts(int boards, int holds, int routes, int variants, int placements) {

    static final RowCounts ZERO = new RowCounts(0, 0, 0, 0, 0);

    RowCounts plus(RowCounts other) {
      return new RowCounts(
          boards + other.boards,
          holds + other.holds,
          routes + other.routes,
          variants + other.variants,
          placements + other.placements);
    }
  }

  public int exitCode() {
    return rejected > 0 ? EXIT_REJECTED_ROWS : EXIT_OK;
  }

  /** The two contracted stdout lines (planning/06 section 4). */
  public String summary() {
    return "imported routes="
        + inserted.routes()
        + " variants="
        + inserted.variants()
        + " holds="
        + inserted.holds()
        + " rejected="
        + rejected
        + " provenance="
        + provenanceId
        + System.lineSeparator()
        + "updated routes="
        + updated.routes()
        + " variants="
        + updated.variants()
        + " holds="
        + updated.holds();
  }
}
