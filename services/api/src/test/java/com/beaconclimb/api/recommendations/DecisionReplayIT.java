package com.beaconclimb.api.recommendations;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.catalog.CatalogSeed;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * BC-0124 MEDIUM-9 replay seed (BC-0124c, ahead of BC-0404): a live decision is reproduced from its
 * persisted artifacts alone. The test reads {@code record_json} from the ledger and the candidate
 * snapshot file the record names, checks the file hashes to {@code candidate_set_digest}, and then
 * re-derives every served position with {@link ReferenceHeuristic}, an independent port of the
 * policy formulas that never touches the production ranker: target grade and challenge fit from
 * {@code policy_config}, novelty from the frozen {@code recent_variant_ids}, preference match from
 * the catalog's tags, confidence from the observation count, the left-to-right score sum, the
 * sha256 tie order, the cut to {@code max_routes}, the low-inventory widening and the reason codes.
 * Ids, positions, score bits and reason codes must match {@code ranked_results} exactly, for a cold
 * user, a warm user under the cooldown with and without the PROJECT goal, and a decision the
 * widening rescued.
 */
class DecisionReplayIT extends RecommendationsTestSupport {

  private static final Instant T = Instant.parse("2026-10-14T14:00:00Z");
  private static final int MAX_EXCLUDED = 100;
  private static final int STRICT_SURVIVORS_UNDER_MIN = 2;

  /** What the replay established about the decision, for scenario-level assertions. */
  private record Replay(
      boolean coldStart,
      boolean widened,
      int cooledOnly,
      List<ReferenceHeuristic.Result> results) {}

  private record Frozen(ReferenceHeuristic.Input input, boolean strict, boolean cooledOnly) {}

  @BeforeEach
  void pinClock() {
    testClock.fix(T);
  }

  @Test
  void coldStartDecisionReplaysBitForBitFromRecordAndSnapshot() {
    Account account = registerReady("INTERMEDIATE");
    List<String> pool = candidateIds();
    List<String> exclude = List.of(pool.get(0), pool.get(5), "ghost-0001:40");

    JsonNode response =
        recommendOk(
            account.token(), recommendationBody("TECHNIQUE", 8, UUID.randomUUID(), exclude));

    Replay replay = replay(account, "INTERMEDIATE", response);
    assertThat(replay.coldStart()).isTrue();
    assertThat(replay.widened()).isFalse();
    assertThat(replay.cooledOnly()).isZero();
    assertThat(replay.results()).hasSize(8);
    assertThat(replay.results())
        .allSatisfy(r -> assertThat(r.reasonCodes()).contains("COLD_START_HEURISTIC"));
  }

  @Test
  void warmDecisionUnderCooldownReplaysWithAndWithoutTheProjectGoal() {
    Account account = registerReady("ADVANCED");
    List<String> pool = candidateIds();
    attemptAt(account, pool.get(0), T.minus(Duration.ofHours(1)));
    attemptAt(account, pool.get(1), T.minus(Duration.ofDays(3)));
    attemptAt(account, pool.get(2), T.minus(Duration.ofDays(100)));
    List<String> exclude = List.of(pool.get(3));

    JsonNode strength =
        recommendOk(
            account.token(), recommendationBody("STRENGTH", 10, UUID.randomUUID(), exclude));
    JsonNode project =
        recommendOk(account.token(), recommendationBody("PROJECT", 10, UUID.randomUUID(), exclude));

    Replay cooled = replay(account, "ADVANCED", strength);
    assertThat(cooled.coldStart()).isFalse();
    assertThat(cooled.widened()).isFalse();
    assertThat(cooled.cooledOnly()).isEqualTo(1);
    assertThat(cooled.results()).hasSize(10);
    assertThat(cooled.results())
        .extracting(ReferenceHeuristic.Result::routeVariantId)
        .doesNotContain(pool.get(0), pool.get(3));
    Replay projecting = replay(account, "ADVANCED", project);
    assertThat(projecting.coldStart()).isFalse();
    assertThat(projecting.cooledOnly()).isZero();
    assertThat(projecting.results()).hasSize(10);
  }

  @Test
  void lowInventoryWidenedDecisionReplays() {
    Account account = registerReady("BEGINNER");
    List<String> pool = candidateIds();
    assertThat(pool.size()).isGreaterThan(MAX_EXCLUDED + STRICT_SURVIVORS_UNDER_MIN);
    List<String> exclude = pool.subList(0, MAX_EXCLUDED);
    List<String> cooled = pool.subList(MAX_EXCLUDED, pool.size() - STRICT_SURVIVORS_UNDER_MIN);
    for (String id : cooled) {
      attemptAt(account, id, T.minus(Duration.ofHours(1)));
    }

    JsonNode response =
        recommendOk(
            account.token(), recommendationBody("TECHNIQUE", 5, UUID.randomUUID(), exclude));

    Replay replay = replay(account, "BEGINNER", response);
    assertThat(replay.coldStart()).isFalse();
    assertThat(replay.widened()).isTrue();
    assertThat(replay.cooledOnly()).isEqualTo(cooled.size());
    assertThat(replay.results()).hasSize(5);
    assertThat(replay.results())
        .allSatisfy(r -> assertThat(r.reasonCodes()).contains("LOW_INVENTORY_WIDENED"));
  }

  /**
   * Reproduces the decision behind {@code response} from the ledger row and the snapshot file and
   * asserts {@code ranked_results} (and the served response) match position by position.
   */
  private Replay replay(Account account, String band, JsonNode response) {
    UUID decisionId = UUID.fromString(response.get("decision_id").asString());
    Map<String, Object> row = decisionRow(decisionId);
    JsonNode record = tree((String) row.get("record_json"));
    String digest = (String) row.get("candidate_set_digest");
    byte[] bytes = snapshotFile(digest);
    assertThat(ReferenceHeuristic.sha256Hex(bytes))
        .isEqualTo(digest)
        .isEqualTo(record.get("candidate_set_digest").asString());
    JsonNode snapshot = tree(new String(bytes, StandardCharsets.UTF_8));
    assertThat(snapshot.get("user_id").asString()).isEqualTo(account.userId().toString());

    ReferenceHeuristic.Config config =
        ReferenceHeuristic.Config.fromJson(record.get("policy_config"));
    assertThat(config.policyVersion()).isEqualTo(record.get("policy_version").asString());
    JsonNode constraints = record.get("request_constraints");
    String goal = constraints.get("goal").asString();
    int maxRoutes = constraints.get("max_routes").asInt();
    Set<String> excluded = new HashSet<>();
    constraints.get("exclude_route_variant_ids").forEach(id -> excluded.add(id.asString()));
    UUID sessionId = UUID.fromString(record.get("session_id").asString());

    JsonNode features = snapshot.get("user_features");
    double target = config.targetGrade(band, goal);
    assertThat(bits(features.get("target_grade_numeric").doubleValue())).isEqualTo(bits(target));
    List<String> recent = new ArrayList<>();
    features.get("recent_variant_ids").forEach(id -> recent.add(id.asString()));
    boolean coldStart = recent.isEmpty() && features.get("max_sent_grade_numeric_90d").isNull();

    Map<String, List<String>> tags = routeTags();
    Map<String, Frozen> frozen = new LinkedHashMap<>();
    Map<String, Boolean> eligibleFlags = new HashMap<>();
    for (JsonNode candidate : snapshot.get("candidates")) {
      String id = candidate.get("route_variant_id").asString();
      JsonNode filters = candidate.get("filter_results");
      JsonNode f = candidate.get("features");
      assertThat(filters.get("board").asBoolean()).as(id).isTrue();
      assertThat(filters.get("angle").asBoolean()).as(id).isTrue();
      assertThat(filters.get("active").asBoolean()).as(id).isTrue();
      assertThat(filters.get("exclude").asBoolean()).as(id).isEqualTo(!excluded.contains(id));
      boolean cooldownActive = f.get("cooldown_active").asBoolean();
      assertThat(filters.get("cooldown").asBoolean())
          .as(id)
          .isEqualTo(goal.equals("PROJECT") || !cooldownActive);
      double novelty = ReferenceHeuristic.novelty(recent, id);
      double preference =
          ReferenceHeuristic.preferenceMatch(tags.get(id), config.preferredTagsFor(goal));
      assertThat(f.get("novelty").doubleValue()).as(id).isEqualTo(novelty);
      assertThat(f.get("preference_match").doubleValue()).as(id).isEqualTo(preference);
      boolean othersPass = !excluded.contains(id);
      boolean cooldownPassed = filters.get("cooldown").asBoolean();
      frozen.put(
          id,
          new Frozen(
              new ReferenceHeuristic.Input(
                  id,
                  f.get("grade_numeric").doubleValue(),
                  f.get("grade_observation_count").asInt(),
                  novelty,
                  preference),
              othersPass && cooldownPassed,
              othersPass && !cooldownPassed));
      eligibleFlags.put(id, candidate.get("eligible").asBoolean());
    }
    int strict = (int) frozen.values().stream().filter(Frozen::strict).count();
    int cooledOnly = (int) frozen.values().stream().filter(Frozen::cooledOnly).count();
    boolean widened = ReferenceHeuristic.widened(config, strict, cooledOnly);
    List<ReferenceHeuristic.Input> eligible = new ArrayList<>();
    Set<String> expectedEligible = new HashSet<>();
    for (Frozen entry : frozen.values()) {
      if (entry.strict() || (widened && entry.cooledOnly())) {
        eligible.add(entry.input());
        expectedEligible.add(entry.input().routeVariantId());
      }
    }
    Set<String> flaggedEligible = new HashSet<>();
    eligibleFlags.forEach(
        (id, flag) -> {
          if (flag) {
            flaggedEligible.add(id);
          }
        });
    assertThat(flaggedEligible).isEqualTo(expectedEligible);

    List<ReferenceHeuristic.Result> expected =
        ReferenceHeuristic.rank(
            config, account.userId(), sessionId, target, eligible, maxRoutes, widened, coldStart);
    JsonNode ranked = record.get("ranked_results");
    assertThat(ranked).hasSize(expected.size());
    for (int i = 0; i < expected.size(); i++) {
      ReferenceHeuristic.Result want = expected.get(i);
      JsonNode got = ranked.get(i);
      assertThat(got.get("route_variant_id").asString())
          .as("position %d", i + 1)
          .isEqualTo(want.routeVariantId());
      assertThat(got.get("position").asInt()).isEqualTo(want.position()).isEqualTo(i + 1);
      assertThat(bits(got.get("score").doubleValue()))
          .as("score bits at position %d (%s)", i + 1, want.routeVariantId())
          .isEqualTo(bits(want.score()));
      List<String> codes = new ArrayList<>();
      got.get("reason_codes").forEach(code -> codes.add(code.asString()));
      assertThat(codes).as("position %d", i + 1).isEqualTo(want.reasonCodes());
    }
    assertThat(servedIds(response))
        .isEqualTo(expected.stream().map(ReferenceHeuristic.Result::routeVariantId).toList());
    assertThat(servedScoreBits(response))
        .isEqualTo(expected.stream().map(r -> bits(r.score())).toList());
    assertThat(row.get("cold_start")).isEqualTo(coldStart);
    return new Replay(coldStart, widened, cooledOnly, expected);
  }

  private Map<String, List<String>> routeTags() {
    Map<String, List<String>> tags = new HashMap<>();
    jdbc.sql(
            "SELECT r.provider_route_uuid || ':' || v.angle_deg AS id, r.tags_json::text AS tags"
                + " FROM route_variants v JOIN routes r ON r.route_id = v.route_id"
                + " WHERE r.board_id = :board AND v.angle_deg = :angle")
        .param("board", CatalogSeed.BOARD_ID)
        .param("angle", ANGLE)
        .query(
            rs -> {
              List<String> list = new ArrayList<>();
              tree(rs.getString("tags")).forEach(tag -> list.add(tag.asString()));
              tags.put(rs.getString("id"), list);
            });
    return tags;
  }

  private static long bits(double value) {
    return Double.doubleToRawLongBits(value);
  }
}
