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
    Policy active = registry.active();
    PolicyRegistry.StoredPolicy stored = registry.find("heuristic-v1").orElseThrow();

    assertThat(active).isEqualTo(loader.load());
    assertThat(active.codeDigest()).isEqualTo(loader.codeDigest());
    assertThat(stored.active()).isTrue();
    assertThat(stored.codeDigest()).isEqualTo(loader.codeDigest());
    assertThat(Jcs.canonicalize(stored.serializedConfig()))
        .isEqualTo(Jcs.canonicalize(loader.rawJson()));
    assertThat(Policy.fromJson(stored.serializedConfig()).withCodeDigest(stored.codeDigest()))
        .isEqualTo(active);
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
