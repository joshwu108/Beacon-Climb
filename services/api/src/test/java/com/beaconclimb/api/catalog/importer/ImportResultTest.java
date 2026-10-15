package com.beaconclimb.api.catalog.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.catalog.importer.ImportResult.RowCounts;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImportResultTest {

  @Test
  void summaryPrintsTheContractedLinesAndExitCodeFlagsRejections() {
    UUID id = UUID.fromString("019a0000-0000-7000-8000-000000000001");
    ImportResult clean =
        new ImportResult(
            id, true, new RowCounts(1, 4, 2, 3, 6), new RowCounts(0, 0, 0, 0, 0), 0, null);
    ImportResult dirty =
        new ImportResult(
            id, false, new RowCounts(0, 0, 0, 0, 0), new RowCounts(1, 4, 2, 3, 0), 1, null);

    assertThat(clean.summary().lines().toList())
        .containsExactly(
            "imported routes=2 variants=3 holds=4 rejected=0 provenance=" + id,
            "updated routes=0 variants=0 holds=0");
    assertThat(clean.exitCode()).isZero();
    assertThat(dirty.summary().lines().toList())
        .containsExactly(
            "imported routes=0 variants=0 holds=0 rejected=1 provenance=" + id,
            "updated routes=2 variants=3 holds=4");
    assertThat(dirty.exitCode()).isEqualTo(2);
  }
}
