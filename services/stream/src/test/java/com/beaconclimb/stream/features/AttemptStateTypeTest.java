package com.beaconclimb.stream.features;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.stream.AttemptEvent;
import java.time.Instant;
import java.util.UUID;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.PojoTypeInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The state type is a Flink POJO (schema-evolvable PojoSerializer), never a Kryo generic type. */
class AttemptStateTypeTest {

  @Test
  @DisplayName("AttemptState is analysed as PojoTypeInfo with all six fields")
  void stateIsPojo() {
    TypeInformation<AttemptState> type = ClimberFeaturesFunction.ATTEMPT_STATE;

    assertThat(type).isInstanceOf(PojoTypeInfo.class);
    assertThat(type.getArity()).isEqualTo(6);
    assertThat(TypeInformation.of(AttemptState.class)).isInstanceOf(PojoTypeInfo.class);
  }

  @Test
  @DisplayName(
      "a retraction becomes a tombstone that keeps the revision; a record keeps the fields")
  void fromEvent() {
    Instant at = Instant.parse("2026-10-14T14:00:00.250Z");
    UUID id = TestEvents.id(9);
    AttemptEvent recorded = TestEvents.recorded(TestEvents.USER_A, id, at, "r:40", true, 17.5);
    AttemptEvent retracted = TestEvents.retracted(TestEvents.USER_A, id, 4, at);

    AttemptState active = AttemptState.of(recorded);
    AttemptState tombstone = AttemptState.of(retracted);

    assertThat(active).isEqualTo(new AttemptState(1, false, at.toEpochMilli(), "r:40", true, 17.5));
    assertThat(active.toRow(id))
        .isEqualTo(new com.beaconclimb.features.AttemptFeatureRow(id, 1, "r:40", at, true, 17.5));
    assertThat(tombstone.tombstone).isTrue();
    assertThat(tombstone.revision).isEqualTo(4);
    assertThat(
            AttemptState.of(TestEvents.recorded(TestEvents.USER_A, id, at, "r:40", false, null))
                .gradeNumeric)
        .isNull();
  }
}
