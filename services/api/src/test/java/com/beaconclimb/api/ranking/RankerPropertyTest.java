package com.beaconclimb.api.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.identity.preferences.Goal;
import com.beaconclimb.api.identity.preferences.GradeBand;
import com.beaconclimb.api.ranking.FilterOutcome.FilterReason;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * BC-0110 acceptance properties over random candidate sets and contexts (1000 tries each):
 * determinism including under shuffling, no filtered candidate served, the cooldown rule, the
 * output size rule, non-increasing scores and the sha256 tie order.
 */
class RankerPropertyTest {

  private static final Policy POLICY = RankingFixtures.policy();
  private static final List<String> TAGS =
      List.of("crimpy", "slopers", "dynamic", "technical", "powerful", "balancy");

  @Property(tries = 1000)
  void sameInputsGiveTheSameResultEvenWhenCandidatesAreShuffled(
      @ForAll("candidates") List<Candidate> candidates,
      @ForAll("contexts") UserContext context,
      @ForAll @IntRange(min = 0, max = 12) int maxRoutes,
      @ForAll long seed) {
    List<Candidate> shuffled = new ArrayList<>(candidates);
    Collections.shuffle(shuffled, new Random(seed));

    RankResult first = Ranker.rank(candidates, context, POLICY, maxRoutes);
    RankResult second = Ranker.rank(candidates, context, POLICY, maxRoutes);
    RankResult reordered = Ranker.rank(shuffled, context, POLICY, maxRoutes);

    assertThat(second).isEqualTo(first);
    assertThat(reordered.ranked()).isEqualTo(first.ranked());
    assertThat(reordered.lowInventoryWidened()).isEqualTo(first.lowInventoryWidened());
    assertThat(reordered.rejected()).containsExactlyInAnyOrderElementsOf(first.rejected());
  }

  @Property(tries = 1000)
  void noInactiveIncompatibleOrExcludedCandidateIsEverServed(
      @ForAll("candidates") List<Candidate> candidates,
      @ForAll("contexts") UserContext context,
      @ForAll @IntRange(min = 0, max = 12) int maxRoutes) {
    RankResult result = Ranker.rank(candidates, context, POLICY, maxRoutes);

    for (Ranked ranked : result.ranked()) {
      Candidate candidate = byId(candidates, ranked.routeVariantId());
      assertThat(candidate.boardId()).isEqualTo(context.boardId());
      assertThat(candidate.angleDeg()).isEqualTo(context.angleDeg());
      assertThat(candidate.validationStatus()).isEqualTo(Candidate.ACTIVE);
      assertThat(context.excludeIds()).doesNotContain(candidate.routeVariantId());
    }
  }

  @Property(tries = 1000)
  void cooldownHoldsUnlessGoalIsProjectOrInventoryWasWidened(
      @ForAll("candidates") List<Candidate> candidates,
      @ForAll("contexts") UserContext context,
      @ForAll @IntRange(min = 0, max = 12) int maxRoutes) {
    RankResult result = Ranker.rank(candidates, context, POLICY, maxRoutes);
    long strictEligible = candidates.stream().filter(c -> eligible(c, context, true)).count();
    long cooled =
        candidates.stream()
            .filter(c -> eligible(c, context, false) && !eligible(c, context, true))
            .count();

    if (context.goal() == Goal.PROJECT) {
      assertThat(result.lowInventoryWidened()).isFalse();
      assertThat(result.rejected()).noneMatch(r -> r.reason() == FilterReason.COOLDOWN);
      return;
    }
    if (result.lowInventoryWidened()) {
      assertThat(strictEligible).isLessThan(POLICY.lowInventoryMin());
      assertThat(cooled).isPositive();
      assertThat(result.ranked())
          .allSatisfy(r -> assertThat(r.reasonCodes()).contains(ReasonCode.LOW_INVENTORY_WIDENED));
      return;
    }
    for (Ranked ranked : result.ranked()) {
      Candidate candidate = byId(candidates, ranked.routeVariantId());
      assertThat(CandidateFilter.inCooldown(candidate, context.now(), POLICY)).isFalse();
      assertThat(ranked.reasonCodes()).doesNotContain(ReasonCode.LOW_INVENTORY_WIDENED);
    }
    assertThat(result.rejected().stream().filter(r -> r.reason() == FilterReason.COOLDOWN).count())
        .isEqualTo(cooled);
  }

  @Property(tries = 1000)
  void outputSizeIsMinOfMaxRoutesAndEligibleAndAtLeastThreeWhenPossible(
      @ForAll("candidates") List<Candidate> candidates,
      @ForAll("contexts") UserContext context,
      @ForAll @IntRange(min = 3, max = 12) int maxRoutes) {
    RankResult result = Ranker.rank(candidates, context, POLICY, maxRoutes);
    boolean strict = context.goal() != Goal.PROJECT && !result.lowInventoryWidened();
    long eligible = candidates.stream().filter(c -> eligible(c, context, strict)).count();

    assertThat(result.ranked()).hasSize((int) Math.min(maxRoutes, eligible));
    if (eligible >= 3) {
      assertThat(result.ranked().size()).isGreaterThanOrEqualTo(3);
    }
    for (int i = 0; i < result.ranked().size(); i++) {
      assertThat(result.ranked().get(i).position()).isEqualTo(i + 1);
    }
  }

  @Property(tries = 1000)
  void scoresAreNonIncreasingAndTiesFollowTheSha256Key(
      @ForAll("candidates") List<Candidate> candidates,
      @ForAll("contexts") UserContext context,
      @ForAll @IntRange(min = 1, max = 12) int maxRoutes) {
    List<Ranked> ranked = Ranker.rank(candidates, context, POLICY, maxRoutes).ranked();

    for (int i = 1; i < ranked.size(); i++) {
      Ranked previous = ranked.get(i - 1);
      Ranked current = ranked.get(i);
      assertThat(current.score()).isLessThanOrEqualTo(previous.score());
      if (current.score() == previous.score()) {
        assertThat(key(context, previous.routeVariantId()))
            .isLessThan(key(context, current.routeVariantId()));
      }
      assertThat(Double.doubleToRawLongBits(current.score()))
          .isEqualTo(Double.doubleToRawLongBits(Scoring.score(current.inputs(), POLICY.weights())));
    }
  }

  /**
   * BC-0124 item 8: with duplicate ids the ranker behaves as if only the first of each were given.
   */
  @Property(tries = 1000)
  void duplicateIdsAreServedAtMostOnceAndOnlyTheFirstOccurrenceCounts(
      @ForAll("candidatesWithDuplicates") List<Candidate> candidates,
      @ForAll("contexts") UserContext context,
      @ForAll @IntRange(min = 0, max = 12) int maxRoutes) {
    RankResult result = Ranker.rank(candidates, context, POLICY, maxRoutes);

    assertThat(result.ranked()).extracting(Ranked::routeVariantId).doesNotHaveDuplicates();
    assertThat(result.rejected()).extracting(FilterOutcome::routeVariantId).doesNotHaveDuplicates();
    List<Candidate> firstOccurrences = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Candidate candidate : candidates) {
      if (seen.add(candidate.routeVariantId())) {
        firstOccurrences.add(candidate);
      }
    }
    assertThat(result).isEqualTo(Ranker.rank(firstOccurrences, context, POLICY, maxRoutes));
  }

  @Provide
  Arbitrary<List<Candidate>> candidates() {
    return candidate().list().ofMinSize(0).ofMaxSize(16).uniqueElements(Candidate::routeVariantId);
  }

  /** The same candidates without the uniqueness constraint: duplicate ids are frequent. */
  @Provide
  Arbitrary<List<Candidate>> candidatesWithDuplicates() {
    return candidate().list().ofMinSize(0).ofMaxSize(16);
  }

  private static Arbitrary<Candidate> candidate() {
    return Combinators.combine(
            Arbitraries.integers().between(0, 29),
            Arbitraries.of(RankingFixtures.BOARD, RankingFixtures.BOARD, "other-board"),
            Arbitraries.of(RankingFixtures.ANGLE, RankingFixtures.ANGLE, 35),
            Arbitraries.of(10.0, 14.0, 17.5, 18.0, 19.0, 21.0, 24.0, 30.0, 19.0, 18.0)
                .injectNull(0.1),
            Arbitraries.integers().between(0, 20),
            Arbitraries.of(
                Candidate.ACTIVE, Candidate.ACTIVE, Candidate.ACTIVE, "RETIRED", "INVALID"),
            Arbitraries.subsetOf(TAGS).ofMaxSize(2),
            Arbitraries.integers().between(0, 100).injectNull(0.5))
        .as(
            (id, board, angle, grade, obs, status, tags, hoursAgo) ->
                new Candidate(
                    "v" + id + ":" + angle,
                    board,
                    angle,
                    grade,
                    obs,
                    status,
                    List.copyOf(tags),
                    hoursAgo == null
                        ? null
                        : RankingFixtures.NOW.minus(Duration.ofHours(hoursAgo))));
  }

  @Provide
  Arbitrary<UserContext> contexts() {
    Arbitrary<List<String>> ids =
        Arbitraries.integers()
            .between(0, 29)
            .map(i -> "v" + i + ":" + RankingFixtures.ANGLE)
            .list()
            .ofMaxSize(5);
    return Combinators.combine(
            Arbitraries.of(Goal.class),
            Arbitraries.of(GradeBand.class),
            ids,
            ids,
            Arbitraries.of(12.0, 18.0, 25.0).injectNull(0.5))
        .as(
            (goal, band, recent, exclude, maxSent) ->
                RankingFixtures.context(goal, band, recent, Set.copyOf(exclude), maxSent));
  }

  /** The hard filters restated independently; {@code strict} applies the cooldown. */
  private static boolean eligible(Candidate c, UserContext context, boolean strict) {
    boolean base =
        c.boardId().equals(context.boardId())
            && c.angleDeg() == context.angleDeg()
            && c.validationStatus().equals(Candidate.ACTIVE)
            && !context.excludeIds().contains(c.routeVariantId());
    if (!base) {
      return false;
    }
    if (!strict || context.goal() == Goal.PROJECT) {
      return true;
    }
    Instant threshold = context.now().minus(Duration.ofHours(POLICY.cooldownHours()));
    return c.lastAttemptedAt() == null || !c.lastAttemptedAt().isAfter(threshold);
  }

  private static Candidate byId(List<Candidate> candidates, String id) {
    return candidates.stream()
        .filter(c -> c.routeVariantId().equals(id))
        .collect(Collectors.toList())
        .get(0);
  }

  private static String key(UserContext context, String id) {
    return TieBreak.key(POLICY.policyVersion(), context.userId(), context.sessionId(), id);
  }
}
