package com.beaconclimb.api.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.AbstractPostgresIT;
import com.beaconclimb.api.platform.json.Jcs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Startup registers heuristic-v1 in ranking_policies, active, and the row round-trips. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PolicyRegistryIT extends AbstractPostgresIT {

  @Autowired PolicyRegistry registry;
  @Autowired PolicyLoader loader;
  @Autowired JdbcClient jdbc;

  @Test
  void startupUpsertsTheBundledPolicyAsActiveAndActiveRoundTrips() {
    // BC-0210: both bundled policies are registered; heuristic-v2 is the active default and
    // heuristic-v1 stays stored (inactive) and selectable.
    Policy active = registry.active();
    PolicyLoader v2 = new PolicyLoader(PolicyLoader.V2_RESOURCE);
    PolicyRegistry.StoredPolicy storedV2 = registry.find("heuristic-v2").orElseThrow();
    PolicyRegistry.StoredPolicy storedV1 = registry.find("heuristic-v1").orElseThrow();

    assertThat(active).isEqualTo(v2.load());
    assertThat(active.usesModel()).isTrue();
    assertThat(active.codeDigest()).isEqualTo(loader.codeDigest());
    assertThat(storedV2.active()).isTrue();
    assertThat(storedV1.active()).isFalse();
    assertThat(storedV2.codeDigest()).isEqualTo(loader.codeDigest());
    assertThat(Jcs.canonicalize(storedV2.serializedConfig()))
        .isEqualTo(Jcs.canonicalize(v2.rawJson()));
    assertThat(Jcs.canonicalize(storedV1.serializedConfig()))
        .isEqualTo(Jcs.canonicalize(loader.rawJson()));
    assertThat(Policy.fromJson(storedV2.serializedConfig()).withCodeDigest(storedV2.codeDigest()))
        .isEqualTo(active);
    assertThat(registry.policy("heuristic-v1")).contains(loader.load());
  }

  @Test
  void registeringAgainIsIdempotentAndDeactivatesOtherVersions() {
    jdbc.sql(
            "INSERT INTO ranking_policies (policy_version, serialized_config, code_digest,"
                + " created_at, active) VALUES ('registry-it-old', '{}'::jsonb, :digest, now(),"
                + " true) ON CONFLICT (policy_version) DO UPDATE SET active = true")
        .param("digest", "0".repeat(64))
        .update();

    Policy again = registry.register();

    assertThat(again).isEqualTo(registry.active());
    assertThat(registry.find("registry-it-old").orElseThrow().active()).isFalse();
    assertThat(
            jdbc.sql("SELECT count(*) FROM ranking_policies WHERE policy_version = 'heuristic-v1'")
                .query(Integer.class)
                .single())
        .isEqualTo(1);
    assertThat(
            jdbc.sql("SELECT count(*) FROM ranking_policies WHERE active")
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }
}
