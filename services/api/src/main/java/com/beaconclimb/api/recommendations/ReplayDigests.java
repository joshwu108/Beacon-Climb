package com.beaconclimb.api.recommendations;

import com.beaconclimb.api.platform.json.Jcs;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.boot.SpringBootVersion;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The two reproducible digests of a decision record (ADR-0003): {@code rng_seed = sha256(UTF-8(
 * policy_version + "|" + user_id + "|" + session_id))} and {@code environment_digest} = sha256 of
 * the RFC 8785 form of {@code {java_version, spring_boot_version, policy_code_digest,
 * ranking_implementation}}. Both are lowercase hex.
 */
public final class ReplayDigests {

  public static final String RANKING_IMPLEMENTATION = "java-heuristic-v1";

  /** The model-aware scoring path (BC-0210, heuristic-v2). */
  public static final String RANKING_IMPLEMENTATION_V2 = "java-heuristic-v2";

  private ReplayDigests() {}

  public static String rngSeed(String policyVersion, UUID userId, UUID sessionId) {
    String text = policyVersion + "|" + userId + "|" + sessionId;
    return Jcs.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
  }

  public static String environmentDigest(String policyCodeDigest) {
    return environmentDigest(policyCodeDigest, RANKING_IMPLEMENTATION);
  }

  public static String environmentDigest(String policyCodeDigest, String implementation) {
    return Jcs.sha256Hex(
        Jcs.canonicalize(environment(policyCodeDigest, implementation))
            .getBytes(StandardCharsets.UTF_8));
  }

  /** The environment document the digest covers; exposed so audits can reproduce it. */
  public static ObjectNode environment(String policyCodeDigest) {
    return environment(policyCodeDigest, RANKING_IMPLEMENTATION);
  }

  public static ObjectNode environment(String policyCodeDigest, String implementation) {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("java_version", System.getProperty("java.version"));
    node.put("spring_boot_version", String.valueOf(SpringBootVersion.getVersion()));
    node.put("policy_code_digest", policyCodeDigest);
    node.put("ranking_implementation", implementation);
    return node;
  }
}
