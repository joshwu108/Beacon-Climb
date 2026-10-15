package com.beaconclimb.api.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.identity.preferences.Goal;
import com.beaconclimb.api.identity.preferences.GradeBand;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-0003 golden fixture: fed the candidates and user features of
 * contracts/decisions/examples/synthetic-snapshot.json and the policy_config of
 * synthetic-decision.json (challenge 0.4, novelty 0.15, every other weight 0, grade_window 10,
 * target grade 20), the Java ranker reproduces ranked_results exactly: ids, positions, IEEE-754 bit
 * patterns of the scores, reason codes and the sha256 tie order of the two 0.51 scores.
 */
class GoldenFixtureTest {

  private static final Path EXAMPLES =
      Path.of("..", "..", "contracts", "decisions", "examples").toAbsolutePath().normalize();

  @Test
  void reproducesTheSyntheticDecisionBitExactly() throws IOException {
    JsonMapper json = JsonMapper.builder().build();
    JsonNode snapshot = json.readTree(read("synthetic-snapshot.json"));
    JsonNode record = json.readTree(read("synthetic-decision.json"));
    JsonNode constraints = record.get("request_constraints");
    JsonNode config = record.get("policy_config");
    assertThat(config.get("other_weights").asInt()).isZero();

    Policy policy =
        RankingFixtures.fixturePolicy(
            config.get("challenge_weight").doubleValue(),
            config.get("novelty_weight").doubleValue(),
            snapshot.get("user_features").get("target_grade_numeric").doubleValue());
    assertThat(policy.gradeWindow()).isEqualTo(config.get("grade_window").doubleValue());
    List<String> recent = new ArrayList<>();
    snapshot
        .get("user_features")
        .get("recent_variant_ids")
        .forEach(id -> recent.add(id.asString()));
    UserContext context =
        new UserContext(
            UUID.fromString(record.get("user_id").asString()),
            UUID.fromString(record.get("session_id").asString()),
            constraints.get("board_id").asString(),
            constraints.get("angle_deg").asInt(),
            Goal.valueOf(constraints.get("goal").asString()),
            GradeBand.INTERMEDIATE,
            recent,
            Set.of(),
            null,
            Instant.parse(record.get("requested_at").asString()));
    List<Candidate> candidates = new ArrayList<>();
    for (JsonNode candidate : snapshot.get("candidates")) {
      JsonNode features = candidate.get("features");
      candidates.add(
          new Candidate(
              candidate.get("route_variant_id").asString(),
              context.boardId(),
              context.angleDeg(),
              features.get("grade_numeric").doubleValue(),
              features.get("grade_observation_count").asInt(),
              candidate.get("eligible").asBoolean() ? Candidate.ACTIVE : "RETIRED",
              List.of(),
              null));
    }

    RankResult result =
        Ranker.rank(candidates, context, policy, constraints.get("max_routes").asInt());

    JsonNode expected = record.get("ranked_results");
    assertThat(result.ranked()).hasSize(expected.size());
    assertThat(result.coldStart()).isTrue();
    assertThat(result.lowInventoryWidened()).isFalse();
    for (int i = 0; i < expected.size(); i++) {
      JsonNode want = expected.get(i);
      Ranked got = result.ranked().get(i);
      assertThat(got.routeVariantId()).isEqualTo(want.get("route_variant_id").asString());
      assertThat(got.position()).isEqualTo(want.get("position").asInt());
      assertThat(Double.doubleToRawLongBits(got.score()))
          .as("score bits of %s", got.routeVariantId())
          .isEqualTo(Double.doubleToRawLongBits(want.get("score").doubleValue()));
      List<String> codes = new ArrayList<>();
      want.get("reason_codes").forEach(code -> codes.add(code.asString()));
      assertThat(got.reasonCodes().stream().map(Enum::name).toList()).isEqualTo(codes);
    }
    assertThat(result.ranked().get(1).score()).isEqualTo(result.ranked().get(2).score());
    assertThat(tie(context, "synthetic-route-005:35"))
        .isLessThan(tie(context, "synthetic-route-003:35"));
  }

  private static String tie(UserContext context, String id) {
    return TieBreak.key("heuristic-v1", context.userId(), context.sessionId(), id);
  }

  private static String read(String name) throws IOException {
    return Files.readString(EXAMPLES.resolve(name), StandardCharsets.UTF_8);
  }
}
