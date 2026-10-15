package com.beaconclimb.api.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.read.ListAppender;
import com.beaconclimb.api.identity.IdentityTestSupport;
import com.beaconclimb.api.platform.web.RequestIdFilter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/**
 * BC-0122 structured logging: the console appender encodes events as one JSON (ECS) line and the
 * access line of an authenticated request carries request_id and the pseudonymous user_id as
 * top-level members, plus method, path template, status and duration, and nothing from the headers
 * or body (TM-16, TM-33).
 */
class StructuredLoggingIT extends IdentityTestSupport {

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @BeforeEach
  void captureLogs() {
    appender.start();
    root().addAppender(appender);
  }

  @AfterEach
  void releaseLogs() {
    root().detachAppender(appender);
    appender.stop();
  }

  @Test
  void accessLineIsJsonWithRequestIdAndUserId() {
    String token = register(uniqueUsername()).token();
    UUID userId = userIdOf(token);
    String requestId = "structured-probe-" + uniqueUsername();

    var response =
        client()
            .get()
            .uri("/v1/me?probe=query-marker")
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set(RequestIdFilter.HEADER, requestId);
                })
            .retrieve()
            .toEntity(String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

    ILoggingEvent access =
        appender.list.stream()
            .filter(event -> event.getLoggerName().equals(RequestLogFilter.class.getName()))
            .filter(event -> requestId.equals(event.getMDCPropertyMap().get(LogFields.REQUEST_ID)))
            .findFirst()
            .orElseThrow();
    String line = new String(consoleEncoder().encode(access), StandardCharsets.UTF_8);

    assertThat(line).endsWith("\n");
    JsonNode json = tree(line);
    assertThat(json.get(LogFields.REQUEST_ID).asString()).isEqualTo(requestId);
    assertThat(json.get(LogFields.USER_ID).asString()).isEqualTo(userId.toString());
    assertThat(json.get("method").asString()).isEqualTo("GET");
    assertThat(json.get("path").asString()).isEqualTo("/v1/me");
    assertThat(json.get("status").asInt()).isEqualTo(200);
    assertThat(json.get("duration_ms").isNumber()).isTrue();
    assertThat(json.get("message").asString()).startsWith("request method=GET path=/v1/me");
    assertThat(json.at("/service/name").asString()).isEqualTo("beacon-api");
    assertThat(json.at("/log/logger").asString()).isEqualTo(RequestLogFilter.class.getName());
    assertThat(line).doesNotContain(token).doesNotContain("query-marker");
  }

  @Test
  void unauthenticatedAccessLineHasNoUserId() {
    String requestId = "structured-anon-" + uniqueUsername();

    client()
        .get()
        .uri("/v1/consent/current")
        .headers(h -> h.set(RequestIdFilter.HEADER, requestId))
        .retrieve()
        .toEntity(String.class);

    Map<String, String> mdc =
        appender.list.stream()
            .filter(event -> event.getLoggerName().equals(RequestLogFilter.class.getName()))
            .filter(event -> requestId.equals(event.getMDCPropertyMap().get(LogFields.REQUEST_ID)))
            .findFirst()
            .orElseThrow()
            .getMDCPropertyMap();
    assertThat(mdc).containsKey(LogFields.REQUEST_ID).doesNotContainKey(LogFields.USER_ID);
  }

  /** The encoder Boot installed from {@code logging.structured.format.console=ecs}. */
  private static Encoder<ILoggingEvent> consoleEncoder() {
    Optional<ConsoleAppender<ILoggingEvent>> console =
        Optional.ofNullable(root().getAppender("CONSOLE"))
            .filter(ConsoleAppender.class::isInstance)
            .map(appender -> (ConsoleAppender<ILoggingEvent>) appender);
    assertThat(console).as("Boot console appender").isPresent();
    assertThat(console.get().getEncoder().getClass().getName())
        .as("structured encoder")
        .contains("StructuredLogEncoder");
    return console.get().getEncoder();
  }

  private static Logger root() {
    return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
  }
}
