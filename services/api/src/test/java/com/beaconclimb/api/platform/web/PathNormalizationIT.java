package com.beaconclimb.api.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.catalog.CatalogSeed;
import com.beaconclimb.api.catalog.importer.CatalogImporter;
import com.beaconclimb.api.identity.ContractSchemas;
import com.beaconclimb.api.identity.IdentityTestSupport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * BC-0123 F2: a disguised path ({@code /%761/me}, {@code /v1;x=1/...}, dot or empty segments) is
 * refused with a 404 NOT_FOUND envelope before authentication and rate limiting see it, so no
 * handler can be reached around the filters. Requests go over a raw socket because HTTP clients
 * normalise or re-encode such paths themselves. Ordinary paths, including a percent-encoded colon
 * in a route id as the web client sends it, are unaffected.
 */
class PathNormalizationIT extends IdentityTestSupport {

  /** Status, headers (lower-cased names) and body of one raw HTTP/1.0 exchange. */
  private record Raw(int status, Map<String, String> headers, String body) {}

  @Autowired CatalogImporter importer;

  @BeforeEach
  void seedDemoBoard() {
    CatalogSeed.seed(importer, jdbc);
  }

  @Test
  void disguisedPathsAreNotFoundBeforeAuthenticationOrRateLimiting() throws IOException {
    assertNotFound(raw("GET", "/%761/me", null, null));
    assertNotFound(raw("POST", "/v1;x=1/auth/register", null, authBody("u_disguise", PASSWORD)));
    assertNotFound(raw("GET", "/v1/me/../me", null, null));
    assertNotFound(raw("GET", "/v1//me", null, null));
    assertNotFound(raw("GET", "/v1/m%65", null, null));
    assertNotFound(raw("GET", "/v1/.", null, null));
  }

  @Test
  void disguisedPublicPostsCarryNoRateLimitHeaderAndConsumeNoToken() throws IOException {
    for (int i = 0; i < 20; i++) {
      Raw refused = raw("POST", "/v1;x/auth/register", null, authBody("u_disguise", PASSWORD));
      assertThat(refused.status()).isEqualTo(404);
      assertThat(refused.headers()).doesNotContainKey("x-ratelimit-remaining");
    }

    Raw genuine = raw("POST", "/v1/auth/session", null, "{}");
    assertThat(genuine.status()).isEqualTo(422);
    assertThat(genuine.headers()).containsKey("x-ratelimit-remaining");
  }

  @Test
  void ordinaryPathsAndEncodedReservedCharactersStillWork() throws IOException {
    String token = register(uniqueUsername()).token();
    String routeId =
        jdbc.sql(
                "SELECT r.provider_route_uuid || ':' || v.angle_deg FROM route_variants v"
                    + " JOIN routes r ON r.route_id = v.route_id"
                    + " WHERE v.validation_status = 'ACTIVE' AND r.board_id = :board"
                    + " ORDER BY r.provider_route_uuid, v.angle_deg LIMIT 1")
            .param("board", CatalogSeed.BOARD_ID)
            .query(String.class)
            .single();

    assertThat(raw("GET", "/v1/me", null, null).status()).isEqualTo(401);
    assertThat(raw("GET", "/v1/me", token, null).status()).isEqualTo(200);
    assertThat(raw("GET", "/v1/consent/current", null, null).status()).isEqualTo(200);
    assertThat(raw("GET", "/v1/routes/" + routeId, token, null).status()).isEqualTo(200);
    assertThat(raw("GET", "/v1/routes/" + routeId.replace(":", "%3A"), token, null).status())
        .isEqualTo(200);
    assertThat(raw("GET", "/health/ready", null, null).status()).isEqualTo(200);
  }

  private void assertNotFound(Raw response) {
    assertThat(response.status()).as(response.body()).isEqualTo(404);
    assertThat(response.headers()).containsKey("x-request-id");
    ContractSchemas.assertConforms("ErrorEnvelope", response.body());
    JsonNode envelope = tree(response.body());
    assertThat(envelope.get("code").asString()).isEqualTo(ErrorCode.NOT_FOUND.name());
    assertThat(envelope.get("request_id").asString())
        .isEqualTo(response.headers().get("x-request-id"));
  }

  private Raw raw(String method, String target, String token, String body) throws IOException {
    byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    StringBuilder head = new StringBuilder();
    // HTTP/1.0 so Tomcat answers with Content-Length instead of chunked encoding.
    head.append(method).append(' ').append(target).append(" HTTP/1.0\r\n");
    head.append("Host: 127.0.0.1:").append(port).append("\r\n");
    head.append("Connection: close\r\n");
    if (token != null) {
      head.append("Authorization: Bearer ").append(token).append("\r\n");
    }
    if (body != null) {
      head.append("Content-Type: application/json\r\n");
      head.append("Content-Length: ").append(payload.length).append("\r\n");
    }
    head.append("\r\n");
    try (Socket socket = new Socket("127.0.0.1", port)) {
      OutputStream out = socket.getOutputStream();
      out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
      out.write(payload);
      out.flush();
      return parse(readAll(socket.getInputStream()));
    }
  }

  private static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    in.transferTo(buffer);
    return buffer.toByteArray();
  }

  private static Raw parse(byte[] bytes) {
    String text = new String(bytes, StandardCharsets.UTF_8);
    int split = text.indexOf("\r\n\r\n");
    String head = split < 0 ? text : text.substring(0, split);
    String body = split < 0 ? "" : text.substring(split + 4);
    String[] lines = head.split("\r\n");
    int status = Integer.parseInt(lines[0].split(" ")[1]);
    Map<String, String> headers = new HashMap<>();
    for (int i = 1; i < lines.length; i++) {
      int colon = lines[i].indexOf(':');
      headers.put(
          lines[i].substring(0, colon).toLowerCase(Locale.ROOT),
          lines[i].substring(colon + 1).trim());
    }
    return new Raw(status, headers, body);
  }
}
