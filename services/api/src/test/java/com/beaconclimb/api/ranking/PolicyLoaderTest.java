package com.beaconclimb.api.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beaconclimb.api.identity.preferences.Goal;
import com.beaconclimb.api.identity.preferences.GradeBand;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The bundled heuristic-v1 resource loads with the planning/06 values and a stable code digest. */
class PolicyLoaderTest {

  @Test
  void heuristicV1LoadsWithThePlannedWeightsAndConstraints() {
    Policy policy = new PolicyLoader().load();

    assertThat(policy.policyVersion()).isEqualTo("heuristic-v1");
    assertThat(policy.weights()).isEqualTo(new Policy.Weights(0.40, 0.25, 0.15, 0.10, 0.10));
    assertThat(policy.gradeWindow()).isEqualTo(10.0);
    assertThat(policy.minObs()).isEqualTo(10);
    assertThat(policy.cooldownHours()).isEqualTo(48);
    assertThat(policy.lowInventoryMin()).isEqualTo(3);
    assertThat(policy.bandTargets())
        .containsEntry(GradeBand.NOVICE, 12.0)
        .containsEntry(GradeBand.BEGINNER, 15.0)
        .containsEntry(GradeBand.INTERMEDIATE, 19.0)
        .containsEntry(GradeBand.ADVANCED, 23.0)
        .containsEntry(GradeBand.EXPERT, 27.0);
    assertThat(policy.goalOffsets())
        .containsEntry(Goal.TECHNIQUE, -1.0)
        .containsEntry(Goal.STRENGTH, 1.0)
        .containsEntry(Goal.ENDURANCE, -2.0)
        .containsEntry(Goal.PROJECT, 3.0);
    assertThat(policy.preferredTagsFor(Goal.TECHNIQUE)).containsExactly("technical", "slopers");
    assertThat(policy.preferredTagsFor(Goal.PROJECT)).containsExactly("dynamic", "crimpy");
  }

  @Test
  void codeDigestIsSixtyFourHexAndStableAcrossLoads() {
    PolicyLoader first = new PolicyLoader();
    PolicyLoader second = new PolicyLoader();

    assertThat(first.codeDigest()).matches("^[0-9a-f]{64}$");
    assertThat(second.codeDigest()).isEqualTo(first.codeDigest());
    assertThat(first.load().codeDigest()).isEqualTo(first.codeDigest());
    assertThat(PolicyLoader.computeCodeDigest()).isEqualTo(first.codeDigest());
  }

  @Test
  void policyJsonMustCarryEveryBandAndGoal() {
    String missingBand = new PolicyLoader().rawJson().replace("\"EXPERT\": 27", "\"EXPERTS\": 27");

    assertThatThrownBy(() -> Policy.fromJson(missingBand))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("EXPERT");
    assertThatThrownBy(() -> new PolicyLoader("policies/does-not-exist.json").load())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void preferredTagsDefaultToEmptyForAGoalTheFileOmits() {
    String withoutProject =
        new PolicyLoader().rawJson().replace("\"PROJECT\": [\"dynamic\", \"crimpy\"]", "\"X\": []");

    Policy policy = Policy.fromJson(withoutProject);

    assertThat(policy.preferredTagsFor(Goal.PROJECT)).isEqualTo(List.of());
    assertThat(policy.preferredTagsFor(Goal.STRENGTH)).containsExactly("crimpy", "dynamic");
  }
}
