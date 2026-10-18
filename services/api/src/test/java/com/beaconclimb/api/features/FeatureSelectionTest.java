package com.beaconclimb.api.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beaconclimb.api.features.FeatureSnapshotRepository.Both;
import com.beaconclimb.features.FeatureComputer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The BC-0310 part 2 read rule as a pure function of the two rows and the stream flag. */
class FeatureSelectionTest {

  private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-00000000000a");
  private static final Instant T = Instant.parse("2026-10-14T14:00:00Z");

  private static FeatureSnapshot row(FeatureSource source, Instant cutoffW) {
    FeatureComputer.Result empty = FeatureComputer.compute(List.of(), cutoffW);
    boolean stream = source == FeatureSource.STREAM;
    return new FeatureSnapshot(
        USER,
        1,
        cutoffW,
        null,
        empty.aggregates(),
        empty.checksum(),
        source,
        stream ? 0 : null,
        stream ? 0L : null,
        cutoffW);
  }

  private static Both both(FeatureSnapshot sync, FeatureSnapshot stream) {
    return new Both(Optional.ofNullable(sync), Optional.ofNullable(stream));
  }

  @Test
  @DisplayName("enabled: STREAM wins when at least as fresh as SYNC, including equal cutoffs")
  void streamWinsWhenAtLeastAsFresh() {
    FeatureSnapshot sync = row(FeatureSource.SYNC, T);
    FeatureSnapshot fresher = row(FeatureSource.STREAM, T.plusSeconds(1));
    FeatureSnapshot equal = row(FeatureSource.STREAM, T);

    assertThat(FeatureSelection.select(both(sync, fresher), true))
        .contains(new FeatureSelection(fresher, null));
    assertThat(FeatureSelection.select(both(sync, equal), true))
        .contains(new FeatureSelection(equal, null));
    assertThat(FeatureSelection.select(both(null, equal), true))
        .contains(new FeatureSelection(equal, null));
  }

  @Test
  @DisplayName("enabled: SYNC with NO_STREAM_ROW or STREAM_OLDER_THAN_SYNC otherwise")
  void syncWithAReasonOtherwise() {
    FeatureSnapshot sync = row(FeatureSource.SYNC, T);
    FeatureSnapshot older = row(FeatureSource.STREAM, T.minus(Duration.ofMinutes(1)));

    assertThat(FeatureSelection.select(both(sync, null), true))
        .contains(new FeatureSelection(sync, FeatureFallbackReason.NO_STREAM_ROW));
    assertThat(FeatureSelection.select(both(sync, older), true))
        .contains(new FeatureSelection(sync, FeatureFallbackReason.STREAM_OLDER_THAN_SYNC));
    assertThat(FeatureSelection.select(both(null, null), true)).isEmpty();
  }

  @Test
  @DisplayName("disabled: SYNC always with STREAM_DISABLED, a leftover STREAM row is ignored")
  void disabledAlwaysSelectsSync() {
    FeatureSnapshot sync = row(FeatureSource.SYNC, T);
    FeatureSnapshot fresher = row(FeatureSource.STREAM, T.plus(Duration.ofDays(1)));

    assertThat(FeatureSelection.select(both(sync, fresher), false))
        .contains(new FeatureSelection(sync, FeatureFallbackReason.STREAM_DISABLED));
    assertThat(FeatureSelection.select(both(sync, null), false))
        .contains(new FeatureSelection(sync, FeatureFallbackReason.STREAM_DISABLED));
    // No SYNC row: empty, so the caller recomputes; the leftover STREAM row is never served.
    assertThat(FeatureSelection.select(both(null, fresher), false)).isEmpty();
    assertThat(FeatureSelection.recomputed(sync, false).fallbackReason())
        .isEqualTo(FeatureFallbackReason.STREAM_DISABLED);
    assertThat(FeatureSelection.recomputed(sync, true).fallbackReason())
        .isEqualTo(FeatureFallbackReason.NO_STREAM_ROW);
  }

  @Test
  @DisplayName("a reason is set exactly when the SYNC row was chosen")
  void reasonAndSourceAreTiedTogether() {
    FeatureSnapshot sync = row(FeatureSource.SYNC, T);
    FeatureSnapshot stream = row(FeatureSource.STREAM, T);

    assertThatThrownBy(() -> new FeatureSelection(sync, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FeatureSelection(stream, FeatureFallbackReason.NO_STREAM_ROW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new FeatureSelection(stream, null).provenance())
        .isEqualTo(new FeatureProvenance(FeatureSource.STREAM, T));
  }
}
