package com.beaconclimb.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beaconclimb.api.identity.api.AuthResponse;
import com.beaconclimb.api.platform.web.RequestIdFilter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * TM-16 / backlog BC-0107 acceptance: with Spring Web and the application at DEBUG, a full
 * register, login, failed login, authenticated read and logout never put the password, the token,
 * the Authorization header or the username into any log line; only user_id and the outcome appear.
 * BC-0122 adds: a write body never reaches the log, the query string never does, and the access
 * line of every request carries its request_id.
 */
class LogScrubbingIT extends IdentityTestSupport {

  /** Server-side loggers raised to DEBUG; Spring MVC logs request and response bodies there. */
  private static final List<String> DEBUG_LOGGERS =
      List.of("org.springframework.web.servlet", "com.beaconclimb");

  /** The test's own RestClient logs the bodies it sends; it is not the system under test. */
  private static final String TEST_CLIENT_LOGGER_PREFIX = "org.springframework.web.client";

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final Map<String, Level> previousLevels = new java.util.HashMap<>();

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
  void secretsNeverReachTheLog() {
    String username = uniqueUsername();
    String password = "hunter2-" + uniqueUsername();
    AuthResponse registered =
        json.readValue(
            post("/v1/auth/register", authBody(username, password), null).getBody(),
            AuthResponse.class);
    AuthResponse loggedIn =
        json.readValue(
            post("/v1/auth/session", authBody(username, password), null).getBody(),
            AuthResponse.class);
    post("/v1/auth/session", authBody(username, password + "-wrong"), null);
    post("/v1/auth/register", authBody(username, password), null);
    assertThat(get("/v1/me", loggedIn.token()).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(delete("/v1/auth/session", registered.token()).getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    String userId = tree(get("/v1/me", loggedIn.token()).getBody()).get("user_id").asString();

    List<String> lines =
        appender.list.stream()
            .filter(event -> !event.getLoggerName().startsWith(TEST_CLIENT_LOGGER_PREFIX))
            .map(LogScrubbingIT::render)
            .toList();

    assertThat(lines).isNotEmpty();
    assertThat(lines).anyMatch(line -> line.contains("login outcome=OK user_id=" + userId));
    assertThat(lines).anyMatch(line -> line.contains("registration outcome=OK user_id=" + userId));
    assertThat(lines).anyMatch(line -> line.contains("login outcome=INVALID_CREDENTIALS"));
    assertThat(lines).noneMatch(line -> line.contains(password));
    assertThat(lines).noneMatch(line -> line.contains(registered.token()));
    assertThat(lines).noneMatch(line -> line.contains(loggedIn.token()));
    assertThat(lines).noneMatch(line -> line.contains(username));
    assertThat(lines).noneMatch(line -> line.contains("Bearer "));
  }

  /**
   * Spring's own DEBUG request line prints the raw URL including the query string, so that
   * assertion is scoped to the application's loggers; the body marker must appear nowhere.
   */
  @Test
  void writeBodiesAndQueryStringsNeverReachTheLogAndAccessLinesCarryTheRequestId() {
    String marker = "notes-marker-" + uniqueUsername();
    String queryMarker = "query-marker-" + uniqueUsername();
    String requestId = "scrub-" + uniqueUsername();
    AuthResponse auth =
        json.readValue(
            post("/v1/auth/register", authBody(uniqueUsername(), PASSWORD), null).getBody(),
            AuthResponse.class);
    String body = "{\"board_id\":\"synthetic-demo-1\",\"notes\":\"" + marker + "\"}";

    ResponseEntity<String> unknownField =
        client()
            .put()
            .uri("/v1/me/preferences?probe=" + queryMarker)
            .contentType(MediaType.APPLICATION_JSON)
            .headers(h -> h.setBearerAuth(auth.token()))
            .header(RequestIdFilter.HEADER, requestId)
            .body(body)
            .retrieve()
            .onStatus(status -> true, (request, response) -> {})
            .toEntity(String.class);
    assertThat(unknownField.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(post("/v1/attempts-not-yet", body, auth.token()).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);

    List<String> lines =
        appender.list.stream()
            .filter(event -> !event.getLoggerName().startsWith(TEST_CLIENT_LOGGER_PREFIX))
            .map(LogScrubbingIT::render)
            .toList();

    assertThat(lines).noneMatch(line -> line.contains(marker));
    assertThat(lines).noneMatch(line -> line.contains(auth.token()));
    assertThat(
            appender.list.stream()
                .filter(event -> event.getLoggerName().startsWith("com.beaconclimb"))
                .map(LogScrubbingIT::render)
                .toList())
        .isNotEmpty()
        .noneMatch(line -> line.contains(queryMarker));
    assertThat(lines)
        .anyMatch(
            line ->
                line.contains("path=/v1/me/preferences")
                    && line.contains("status=422")
                    && line.contains("request_id=" + requestId));
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
