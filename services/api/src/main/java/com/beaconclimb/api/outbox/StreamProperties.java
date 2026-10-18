package com.beaconclimb.api.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code beacon.stream.*} (BC-0301, binding across the streaming tickets). {@code enabled} gates
 * every Kafka bean ({@link OutboxConfig}); the rest is only read when it is true. Env names follow
 * relaxed binding: {@code BEACON_STREAM_ENABLED}, {@code BEACON_STREAM_BOOTSTRAPSERVERS}.
 *
 * @param enabled whether the outbox publisher (and later consumers) run; default false
 * @param bootstrapServers Kafka bootstrap list; {@code kafka:9092} in compose, {@code
 *     127.0.0.1:9094} from the host
 * @param topics topic names of the event contract (contracts/events/README.md)
 * @param publisher outbox publisher tuning
 * @param consumer consumer group ids reserved for the materializer and reconciliation tickets
 * @param materializer climber-features.v1 materializer tuning (BC-0305)
 */
@ConfigurationProperties(prefix = "beacon.stream")
public record StreamProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("127.0.0.1:9094") String bootstrapServers,
    @DefaultValue Topics topics,
    @DefaultValue Publisher publisher,
    @DefaultValue Consumer consumer,
    @DefaultValue Materializer materializer) {

  public record Topics(
      @DefaultValue("attempts.v1") String attempts,
      @DefaultValue("climber-features.v1") String features,
      @DefaultValue("stream-dlq.v1") String dlq,
      @DefaultValue("stream-reconciliation.v1") String reconciliation) {}

  /**
   * @param pollInterval fixed delay between two polls (measured from the end of the previous one)
   * @param batchSize rows locked and sent per poll
   * @param staleAfter age after which an unpublished row counts as stale
   * @param deliveryTimeout producer {@code delivery.timeout.ms}; the batch waits at most this plus
   *     {@code requestTimeout} for its sends before it is rolled back
   * @param requestTimeout producer {@code request.timeout.ms} and {@code max.block.ms}
   */
  public record Publisher(
      @DefaultValue("500ms") Duration pollInterval,
      @DefaultValue("200") int batchSize,
      @DefaultValue("60s") Duration staleAfter,
      @DefaultValue("10s") Duration deliveryTimeout,
      @DefaultValue("5s") Duration requestTimeout) {}

  public record Consumer(
      @DefaultValue("beacon-materializer") String materializerGroupId,
      @DefaultValue("beacon-reconciliation") String reconciliationGroupId) {}

  /**
   * @param maxPollRecords consumer {@code max.poll.records}; each record is one Postgres
   *     transaction and one synchronous offset commit, so a modest batch keeps a stop or rebalance
   *     cheap
   * @param retryBackoff wait before a record whose transaction failed is redelivered; retries are
   *     unlimited because skipping a feature event would silently lose a snapshot (at-least-once)
   */
  public record Materializer(
      @DefaultValue("100") int maxPollRecords, @DefaultValue("1s") Duration retryBackoff) {}
}
