package com.beaconclimb.api.recommendations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beaconclimb.api.identity.preferences.Goal;
import com.beaconclimb.api.platform.json.Jcs;
import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.FieldError;
import java.util.List;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/** {@link RecommendationValidator} against the RecommendationRequest schema, without a server. */
class RecommendationValidatorTest {

  private static final String SESSION = "018f0000-0000-7000-8000-000000000002";

  @Test
  void wellFormedRequestIsNormalisedAndJsonIntegersWithZeroFractionAreAccepted() {
    ValidatedRequest validated =
        RecommendationValidator.validate(
            request(
                "{\"board_id\":\"synthetic-demo-1\",\"angle_deg\":40,\"goal\":\"TECHNIQUE\","
                    + "\"max_routes\":5.0,\"session_id\":\""
                    + SESSION
                    + "\",\"exclude_route_variant_ids\":[\"syn-route-0001:40\"]}"));

    assertThat(validated)
        .isEqualTo(
            new ValidatedRequest(
                "synthetic-demo-1",
                40,
                Goal.TECHNIQUE,
                5,
                UUID.fromString(SESSION),
                List.of("syn-route-0001:40")));
    assertThat(validated.toJsonNode().get("session_id").asString()).isEqualTo(SESSION);
    assertThat(validated.toConstraints().has("session_id")).isFalse();
  }

  @Test
  void everyMissingMemberIsOneRequiredErrorInSchemaOrder() {
    assertThatThrownBy(() -> RecommendationValidator.validate(request("{}")))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
              assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
              assertThat(e.fieldErrors())
                  .extracting(FieldError::field, FieldError::code)
                  .containsExactly(
                      Tuple.tuple("board_id", "required"),
                      Tuple.tuple("angle_deg", "required"),
                      Tuple.tuple("goal", "required"),
                      Tuple.tuple("max_routes", "required"),
                      Tuple.tuple("session_id", "required"),
                      Tuple.tuple("exclude_route_variant_ids", "required"));
            });
  }

  @Test
  void rangeFormatAndItemViolationsAreReportedTogether() {
    String body =
        "{\"board_id\":\"  \",\"angle_deg\":40.5,\"goal\":\"NAPPING\",\"max_routes\":0,"
            + "\"session_id\":\""
            + SESSION.toUpperCase()
            + "\",\"exclude_route_variant_ids\":[\"no colon\",3,\"ok:40\"]}";

    assertThatThrownBy(() -> RecommendationValidator.validate(request(body)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e ->
                assertThat(e.fieldErrors())
                    .extracting(FieldError::field, FieldError::code)
                    .containsExactly(
                        Tuple.tuple("board_id", "type"),
                        Tuple.tuple("angle_deg", "type"),
                        Tuple.tuple("goal", "enum"),
                        Tuple.tuple("max_routes", "minimum"),
                        Tuple.tuple("session_id", "format"),
                        Tuple.tuple("exclude_route_variant_ids[0]", "pattern"),
                        Tuple.tuple("exclude_route_variant_ids[1]", "type")));
  }

  @Test
  void tooManyExclusionsAndTooManyRoutesAreMaximumErrors() {
    StringBuilder ids = new StringBuilder();
    for (int i = 0; i <= RecommendationValidator.MAX_EXCLUDED; i++) {
      ids.append(i == 0 ? "" : ",").append("\"r").append(i).append(":40\"");
    }
    String body =
        "{\"board_id\":\"b\",\"angle_deg\":40,\"goal\":\"PROJECT\",\"max_routes\":21,"
            + "\"session_id\":\""
            + SESSION
            + "\",\"exclude_route_variant_ids\":["
            + ids
            + "]}";

    assertThatThrownBy(() -> RecommendationValidator.validate(request(body)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e ->
                assertThat(e.fieldErrors())
                    .extracting(FieldError::field, FieldError::code)
                    .containsExactly(
                        Tuple.tuple("max_routes", "maximum"),
                        Tuple.tuple("exclude_route_variant_ids", "maxItems")));
  }

  private static RecommendationRequest request(String json) {
    JsonNode node = Jcs.parse(json);
    return new RecommendationRequest(
        node.get("board_id"),
        node.get("angle_deg"),
        node.get("goal"),
        node.get("max_routes"),
        node.get("session_id"),
        node.get("exclude_route_variant_ids"),
        node.get("policy_version"));
  }
}
