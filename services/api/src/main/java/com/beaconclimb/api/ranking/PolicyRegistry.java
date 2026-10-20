package com.beaconclimb.api.ranking;

import static com.beaconclimb.api.platform.jdbc.JdbcTypes.timestamp;

import com.beaconclimb.api.platform.json.Jcs;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registers every bundled policy ({@link PolicyLoader#BUNDLED}: heuristic-v1 and heuristic-v2) in
 * {@code ranking_policies} once every singleton (Flyway included) is up: upserts {@code
 * serialized_config} (JCS of the resource) and {@code code_digest} per version, marks the default
 * ({@code beacon.ranking.default-policy}, heuristic-v2 unless configured) active and every other
 * version inactive, in one transaction. {@link #active()} is the default a request gets when it
 * names no policy; {@link #policy(String)} serves an explicit {@code policy_version} (BC-0210).
 */
@Component
public class PolicyRegistry implements SmartInitializingSingleton {

  /** A stored row, for audits and tests. */
  public record StoredPolicy(
      String policyVersion, String serializedConfig, String codeDigest, boolean active) {}

  private static final Logger log = LoggerFactory.getLogger(PolicyRegistry.class);

  public static final String DEFAULT_POLICY = "heuristic-v2";

  private final PolicyLoader loader;
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final String defaultVersion;
  private volatile Policy active;
  private volatile Map<String, Policy> byVersion = Map.of();

  public PolicyRegistry(
      PolicyLoader loader,
      JdbcClient jdbc,
      TransactionTemplate tx,
      Clock clock,
      @Value("${beacon.ranking.default-policy:" + DEFAULT_POLICY + "}") String defaultVersion) {
    this.loader = loader;
    this.jdbc = jdbc;
    this.tx = tx;
    this.clock = clock;
    this.defaultVersion = defaultVersion;
  }

  @Override
  public void afterSingletonsInstantiated() {
    register();
  }

  /** Idempotent: a second call rewrites the same rows. Returns the default (active) policy. */
  public Policy register() {
    Map<String, Policy> loaded = new LinkedHashMap<>();
    Map<String, String> configs = new LinkedHashMap<>();
    for (String resource : PolicyLoader.BUNDLED) {
      PolicyLoader one = new PolicyLoader(resource);
      Policy policy = Policy.fromJson(one.rawJson()).withCodeDigest(loader.codeDigest());
      loaded.put(policy.policyVersion(), policy);
      configs.put(policy.policyVersion(), Jcs.canonicalize(one.rawJson()));
    }
    Policy chosen = loaded.get(defaultVersion);
    if (chosen == null) {
      throw new IllegalStateException(
          "beacon.ranking.default-policy " + defaultVersion + " is not a bundled policy");
    }
    tx.executeWithoutResult(
        status -> loaded.forEach((version, policy) -> upsert(policy, configs.get(version))));
    tx.executeWithoutResult(status -> markActive(defaultVersion));
    byVersion = Map.copyOf(loaded);
    active = chosen;
    loaded
        .values()
        .forEach(
            policy ->
                log.info(
                    "ranking outcome=POLICY_REGISTERED policy_version={} code_digest={} active={}",
                    policy.policyVersion(),
                    policy.codeDigest(),
                    policy == chosen));
    return chosen;
  }

  /** A bundled policy by version, for a request that names one. */
  public Optional<Policy> policy(String policyVersion) {
    return Optional.ofNullable(byVersion.get(policyVersion));
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
                + " false) ON CONFLICT (policy_version) DO UPDATE SET"
                + " serialized_config = EXCLUDED.serialized_config,"
                + " code_digest = EXCLUDED.code_digest")
        .param("version", policy.policyVersion())
        .param("config", config)
        .param("digest", policy.codeDigest())
        .param("now", timestamp(clock.instant().truncatedTo(ChronoUnit.MILLIS)))
        .update();
  }

  /** Exactly one active row, the default; both updates run in one transaction. */
  private void markActive(String version) {
    jdbc.sql("UPDATE ranking_policies SET active = false WHERE policy_version <> :version")
        .param("version", version)
        .update();
    jdbc.sql("UPDATE ranking_policies SET active = true WHERE policy_version = :version")
        .param("version", version)
        .update();
  }
}
