package com.beaconclimb.api.ranking;

import static com.beaconclimb.api.platform.jdbc.JdbcTypes.timestamp;

import com.beaconclimb.api.platform.json.Jcs;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registers the bundled policy in {@code ranking_policies} once every singleton (Flyway included)
 * is up: upserts {@code serialized_config} (JCS of the resource) and {@code code_digest}, marks it
 * active and every other version inactive, in one transaction. {@link #active()} serves BC-0111.
 */
@Component
public class PolicyRegistry implements SmartInitializingSingleton {

  /** A stored row, for audits and tests. */
  public record StoredPolicy(
      String policyVersion, String serializedConfig, String codeDigest, boolean active) {}

  private static final Logger log = LoggerFactory.getLogger(PolicyRegistry.class);

  private final PolicyLoader loader;
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;
  private volatile Policy active;

  public PolicyRegistry(PolicyLoader loader, JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
    this.loader = loader;
    this.jdbc = jdbc;
    this.tx = tx;
    this.clock = clock;
  }

  @Override
  public void afterSingletonsInstantiated() {
    register();
  }

  /** Idempotent: a second call rewrites the same row. */
  public Policy register() {
    Policy policy = loader.load();
    String config = Jcs.canonicalize(loader.rawJson());
    tx.executeWithoutResult(status -> upsert(policy, config));
    active = policy;
    log.info(
        "ranking outcome=POLICY_REGISTERED policy_version={} code_digest={}",
        policy.policyVersion(),
        policy.codeDigest());
    return policy;
  }

  public Policy active() {
    Policy current = active;
    if (current == null) {
      throw new IllegalStateException("no ranking policy registered yet");
    }
    return current;
  }

  public Optional<StoredPolicy> find(String policyVersion) {
    return jdbc.sql(
            "SELECT policy_version, serialized_config::text AS serialized_config, code_digest,"
                + " active FROM ranking_policies WHERE policy_version = :version")
        .param("version", policyVersion)
        .query(
            (rs, n) ->
                new StoredPolicy(
                    rs.getString("policy_version"),
                    rs.getString("serialized_config"),
                    rs.getString("code_digest"),
                    rs.getBoolean("active")))
        .optional();
  }

  private void upsert(Policy policy, String config) {
    jdbc.sql(
            "INSERT INTO ranking_policies (policy_version, serialized_config, code_digest,"
                + " created_at, active) VALUES (:version, CAST(:config AS jsonb), :digest, :now,"
                + " true) ON CONFLICT (policy_version) DO UPDATE SET"
                + " serialized_config = EXCLUDED.serialized_config,"
                + " code_digest = EXCLUDED.code_digest, active = true")
        .param("version", policy.policyVersion())
        .param("config", config)
        .param("digest", policy.codeDigest())
        .param("now", timestamp(clock.instant().truncatedTo(ChronoUnit.MILLIS)))
        .update();
    jdbc.sql("UPDATE ranking_policies SET active = false WHERE policy_version <> :version")
        .param("version", policy.policyVersion())
        .update();
  }
}
