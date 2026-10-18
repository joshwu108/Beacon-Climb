package com.beaconclimb.api.attempts;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * TM-10 / TM-11 / TM-16 (threat-model check SEC-13): with Spring Web and the application at DEBUG,
 * a POST and a PATCH carrying a marker note never put the note, the body or the token into any log
 * line; the outbox payloads carry only the attributes allowlist and no note under any key; the
 * application logs the outcome with ids only.
 */
class AttemptLogScrubbingIT extends AttemptsTestSupport {

  private static final List<String> DEBUG_LOGGERS =
      List.of("org.springframework.web.servlet", "com.beaconclimb");
  private static final String TEST_CLIENT_LOGGER_PREFIX = "org.springframework.web.client";
  private static final Set<String> ATTRIBUTE_ALLOWLIST =
      Set.of(
          "sent",
          "attempts_count",
          "status",
          "completion_fraction",
          "perceived_effort",
          "grade_numeric");

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final Map<String, Level> previousLevels = new HashMap<>();

  @BeforeEach
  void captureLogs() {
    appender.start();
    root().addAppender(appender);
    for (String name : DEBUG_LOGGERS) {
      Logger logger = (Logger) LoggerFactory.getLogger(name);
      previousLevels.put(name, logger.getLevel());
      logger.setLevel(Level.DEBUG);
    }
  }

  @AfterEach
  void releaseLogs() {
    root().detachAppender(appender);
    appender.stop();
    previousLevels.forEach(
        (name, level) -> ((Logger) LoggerFactory.getLogger(name)).setLevel(level));
  }

  @Test
  void notesNeverReachTheLogOrTheOutbox() {
    Account account = registerWithConsent();
    String createMarker = "notes-marker-create-" + uniqueUsername();
    String patchMarker = "notes-marker-patch-" + uniqueUsername();
    Map<String, Object> body = attemptBody(visibleRouteId(), Instant.parse("2026-10-14T14:00:00Z"));
    body.put("notes", createMarker);

    ResponseEntity<String> created = postAttempt(account.token(), "k-scrub", body);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String attemptId = tree(created.getBody()).get("attempt_id").asString();
    assertThat(postAttempt(account.token(), "k-scrub", body).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    ResponseEntity<String> patched =
        patchAttempt(account.token(), attemptId, "\"1\"", "{\"notes\":\"" + patchMarker + "\"}");
    assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get(ATTEMPTS + "/" + attemptId, account.token()).getBody()).contains(patchMarker);
    assertThat(get(ATTEMPTS, account.token()).getBody()).contains(patchMarker);

    List<String> lines =
        appender.list.stream()
            .filter(event -> !event.getLoggerName().startsWith(TEST_CLIENT_LOGGER_PREFIX))
            .map(AttemptLogScrubbingIT::render)
            .toList();
    assertThat(lines).isNotEmpty();
    assertThat(lines).noneMatch(line -> line.contains(createMarker));
    assertThat(lines).noneMatch(line -> line.contains(patchMarker));
    assertThat(lines).noneMatch(line -> line.contains(account.token()));
    assertThat(lines).noneMatch(line -> line.contains("Bearer "));
    assertThat(lines)
        .anyMatch(
            line ->
                line.contains(
                    "attempt outcome=CREATED user_id="
                        + account.userId()
                        + " attempt_id="
                        + attemptId));
    assertThat(lines)
        .anyMatch(line -> line.contains("attempt outcome=REPLAYED user_id=" + account.userId()));
    assertThat(lines)
        .anyMatch(line -> line.contains("attempt outcome=CORRECTED user_id=" + account.userId()));

    List<Map<String, Object>> events = outboxRows(UUID.fromString(attemptId));
    assertThat(events).hasSize(2);
    for (Map<String, Object> event : events) {
      String payload = event.get("payload_json").toString();
      assertThat(payload)
          .doesNotContain(createMarker)
          .doesNotContain(patchMarker)
          .doesNotContain("note");
      JsonNode node = tree(payload);
      assertThat(node.get("attributes").propertyNames()).isSubsetOf(ATTRIBUTE_ALLOWLIST);
      assertEventConforms(payload);
    }
  }

  private static Logger root() {
    return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
  }

  private static String render(ILoggingEvent event) {
    StringBuilder line = new StringBuilder(event.getFormattedMessage());
    if (event.getThrowableProxy() != null) {
      line.append(' ').append(event.getThrowableProxy().getMessage());
    }
    if (event.getMDCPropertyMap() != null) {
      event.getMDCPropertyMap().forEach((k, v) -> line.append(' ').append(k).append('=').append(v));
    }
    return line.toString();
  }
}
