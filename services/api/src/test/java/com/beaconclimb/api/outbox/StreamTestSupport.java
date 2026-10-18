package com.beaconclimb.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.ApiApplication;
import com.beaconclimb.api.attempts.AttemptsTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The attempts harness with the stream enabled against {@link KafkaTestContainer} and a fast poll.
 * The context is discarded after each class: its scheduled publisher would otherwise keep running
 * from the context cache and publish rows that later core tests assert are still unpublished.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {ApiApplication.class, AttemptsTestSupport.TestClockConfig.class},
    properties = {
      "beacon.http.write-limit-per-minute=1000",
      "beacon.http.public-burst=1000",
      "beacon.stream.enabled=true",
      "beacon.stream.publisher.poll-interval=200ms"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class StreamTestSupport extends AttemptsTestSupport {

  @DynamicPropertySource
  static void streamProperties(DynamicPropertyRegistry registry) {
    registry.add("beacon.stream.bootstrap-servers", KafkaTestContainer::bootstrapServers);
  }

  @Autowired protected OutboxPublisher publisher;
  @Autowired protected OutboxReconciliation reconciliation;
  @Autowired protected MeterRegistry meters;

  /** {@code count} attempts through the real HTTP path, cycling over ten visible routes. */
  protected List<String> createAttempts(Account account, int count) {
    List<String> routes = visibleRouteIds(10);
    List<String> ids = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      ids.add(createAttempt(account, routes.get(i % routes.size())));
    }
    return ids;
  }

  /** event_id (text) to the stored jsonb text of every outbox row of the user's attempts. */
  protected Map<String, String> outboxPayloadsOf(UUID userId) {
    return jdbc
        .sql(
            "SELECT o.event_id::text AS event_id, o.payload_json::text AS payload_json"
                + " FROM attempt_event_outbox o JOIN attempts a ON a.attempt_id = o.aggregate_id"
                + " WHERE a.user_id = :id")
        .param("id", userId)
        .query()
        .listOfRows()
        .stream()
        .collect(
            Collectors.toMap(
                r -> r.get("event_id").toString(), r -> r.get("payload_json").toString()));
  }

  protected int unpublishedRowsOf(UUID userId) {
    return jdbc.sql(
            "SELECT count(*) FROM attempt_event_outbox o JOIN attempts a"
                + " ON a.attempt_id = o.aggregate_id WHERE a.user_id = :id"
                + " AND o.published_at IS NULL")
        .param("id", userId)
        .query(Integer.class)
        .single();
  }

  protected int unpublishedRows() {
    return jdbc.sql("SELECT count(*) FROM attempt_event_outbox WHERE published_at IS NULL")
        .query(Integer.class)
        .single();
  }

  protected double gauge(String name) {
    return meters.get(name).gauge().value();
  }

  /** Fails when any object at any depth has a member called {@code name}. */
  protected static void assertNoMemberNamed(JsonNode node, String name) {
    if (node.isObject()) {
      assertThat(node.has(name)).as("member %s must not appear", name).isFalse();
      node.properties().forEach(entry -> assertNoMemberNamed(entry.getValue(), name));
    } else if (node.isArray()) {
      node.forEach(child -> assertNoMemberNamed(child, name));
    }
  }
}
