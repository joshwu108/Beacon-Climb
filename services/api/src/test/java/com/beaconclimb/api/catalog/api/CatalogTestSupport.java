package com.beaconclimb.api.catalog.api;

import com.beaconclimb.api.catalog.CatalogSeed;
import com.beaconclimb.api.catalog.importer.CatalogImporter;
import com.beaconclimb.api.identity.IdentityTestSupport;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP harness for the catalog endpoints: the identity harness (booted context, RestClient helpers,
 * contract assertions) plus the demo board seeded once per test class (the importer tests may
 * remove it in between) and a registered user's token.
 */
public abstract class CatalogTestSupport extends IdentityTestSupport {

  private static final Set<Class<?>> SEEDED = ConcurrentHashMap.newKeySet();
  private static String sharedToken;

  @Autowired protected CatalogImporter importer;
  @Autowired protected JdbcClient db;
  @Autowired protected JsonMapper mapper;

  protected String token;

  @BeforeEach
  void seedAndAuthenticate() {
    if (SEEDED.add(getClass())) {
      CatalogSeed.seed(importer, db);
    }
    if (sharedToken == null) {
      sharedToken = register(uniqueUsername()).token();
    }
    token = sharedToken;
  }

  protected ResponseEntity<String> getWithHeaders(
      String path, String bearer, Map<String, String> headers) {
    return client()
        .get()
        .uri(path)
        .headers(
            h -> {
              if (bearer != null) {
                h.setBearerAuth(bearer);
              }
              headers.forEach(h::set);
            })
        .retrieve()
        .onStatus(status -> true, (request, response) -> {})
        .toEntity(String.class);
  }

  protected JsonNode body(ResponseEntity<String> response) {
    return mapper.readTree(response.getBody());
  }
}
