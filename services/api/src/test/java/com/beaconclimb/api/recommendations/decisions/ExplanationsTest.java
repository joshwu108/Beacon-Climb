package com.beaconclimb.api.recommendations.decisions;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.ranking.ReasonCode;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** BC-0112: one fixed sentence per reason code, rendered 1:1 in order, from the codes alone. */
class ExplanationsTest {

  @Test
  void everyReasonCodeHasExactlyOneSentence() {
    for (ReasonCode code : ReasonCode.values()) {
      String sentence = Explanations.sentence(code);
      assertThat(sentence).as(code.name()).isNotBlank().endsWith(".");
      assertThat(sentence.split("\\. ")).as(code.name()).hasSize(1);
    }
    assertThat(Explanations.templates()).containsOnlyKeys(ReasonCode.values());
  }

  @Test
  void renderedTextIsOneSentencePerCodeInOrder() {
    List<ReasonCode> codes = Arrays.asList(ReasonCode.values());

    String text = Explanations.render(codes);
    String reversed = Explanations.render(codes.reversed());

    String[] sentences = text.split("(?<=\\.) ");
    assertThat(sentences).hasSize(codes.size());
    for (int i = 0; i < codes.size(); i++) {
      assertThat(sentences[i]).isEqualTo(Explanations.sentence(codes.get(i)));
    }
    assertThat(reversed).isNotEqualTo(text);
    assertThat(Explanations.render(List.of())).isEmpty();
    assertThat(Explanations.render(List.of(ReasonCode.NOVEL_ROUTE)))
        .isEqualTo(Explanations.sentence(ReasonCode.NOVEL_ROUTE));
  }

  @Test
  void sentencesMakeNoPhysicalClaims() {
    for (ReasonCode code : ReasonCode.values()) {
      assertThat(Explanations.sentence(code).toLowerCase())
          .doesNotContain("strong")
          .doesNotContain("injur")
          .doesNotContain("safe");
    }
  }
}
