package com.beaconclimb.stream;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ExternalizedCheckpointRetention;
import org.apache.flink.configuration.RestartStrategyOptions;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the Beacon Climb feature job (BC-0303 skeleton, ADR-0004): attempts.v1 in,
 * climber-features.v1, stream-reconciliation.v1 and stream-dlq.v1 (BC-0302) out, at-least-once end
 * to end. Checkpoints every 10 s to {@code file:///checkpoints} (a compose volume), retained on
 * cancellation; the restart strategy is fixed-delay so a TaskManager restart resumes from the last
 * checkpoint and the committed Kafka offsets travel inside it (the source commits on checkpoint
 * completion).
 */
public final class StreamJob {

  private static final Logger LOG = LoggerFactory.getLogger(StreamJob.class);
  public static final String JOB_NAME = "beacon-climb-features";

  private StreamJob() {}

  public static void main(String[] args) throws Exception {
    StreamConfig config = StreamConfig.fromEnv();
    StreamExecutionEnvironment env =
        StreamExecutionEnvironment.getExecutionEnvironment(flinkConfiguration(config));
    env.setParallelism(config.parallelism());
    build(env, config);
    LOG.info(
        "submitting {} bootstrap={} source={} sinks={},{},{} checkpoints={} parallelism={}",
        JOB_NAME,
        config.bootstrapServers(),
        config.attemptsTopic(),
        config.featuresTopic(),
        config.reconciliationTopic(),
        config.dlqTopic(),
        config.checkpointDir(),
        config.parallelism());
    env.execute(JOB_NAME);
  }

  /** Checkpointing and restart settings as Flink configuration, so the test can reuse them. */
  public static Configuration flinkConfiguration(StreamConfig config) {
    Configuration flink = new Configuration();
    flink.set(CheckpointingOptions.CHECKPOINTING_INTERVAL, config.checkpointInterval());
    flink.set(CheckpointingOptions.CHECKPOINT_STORAGE, "filesystem");
    flink.set(CheckpointingOptions.CHECKPOINTS_DIRECTORY, config.checkpointDir());
    flink.set(
        CheckpointingOptions.EXTERNALIZED_CHECKPOINT_RETENTION,
        ExternalizedCheckpointRetention.RETAIN_ON_CANCELLATION);
    flink.set(RestartStrategyOptions.RESTART_STRATEGY, "fixed-delay");
    flink.set(
        RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_ATTEMPTS, config.restartAttempts());
    flink.set(RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_DELAY, config.restartDelay());
    return flink;
  }

  // kafka-clients 4.x deprecates OffsetResetStrategy, but committedOffsets(...) in
  // flink-connector-kafka 5.0.0-2.2 still takes it; the connector has no replacement overload.
  @SuppressWarnings("deprecation")
  static void build(StreamExecutionEnvironment env, StreamConfig config) {
    KafkaSource<SourceRecord> source =
        KafkaSource.<SourceRecord>builder()
            .setBootstrapServers(config.bootstrapServers())
            .setTopics(config.attemptsTopic())
            .setGroupId(config.groupId())
            // First start: from the beginning of the topic; afterwards the offsets committed at
            // the last completed checkpoint (and restored from it on recovery).
            .setStartingOffsets(OffsetsInitializer.committedOffsets(OffsetResetStrategy.EARLIEST))
            .setProperty("partition.discovery.interval.ms", "60000")
            .setDeserializer(new SourceRecordDeserializationSchema())
            .build();
    // The source watermark strategy is noWatermarks(): event time is assigned after parsing
    // (StreamTopology), because the raw bytes carry no timestamp Flink can read.
    DataStream<SourceRecord> raw =
        env.fromSource(source, WatermarkStrategy.noWatermarks(), "attempts.v1")
            .uid("source-attempts-v1");
    StreamTopology.Outputs outputs =
        StreamTopology.wire(raw, StreamTopology.watermarks(), config.attemptsTopic());
    outputs
        .features()
        .sinkTo(sink(config.bootstrapServers(), config.featuresTopic()))
        .name("sink-climber-features-v1")
        .uid("sink-climber-features-v1");
    outputs
        .late()
        .sinkTo(sink(config.bootstrapServers(), config.reconciliationTopic()))
        .name("sink-stream-reconciliation-v1")
        .uid("sink-stream-reconciliation-v1");
    // BC-0302: records the shared validator rejects, as stream-dlq.v1 records keyed by the
    // original key, with the DLQ headers (ParseAttempts). Same at-least-once sink as the others.
    outputs
        .invalid()
        .sinkTo(sink(config.bootstrapServers(), config.dlqTopic()))
        .name("sink-stream-dlq-v1")
        .uid("sink-stream-dlq-v1");
  }

  static KafkaSink<KeyedBytes> sink(String bootstrapServers, String topic) {
    return KafkaSink.<KeyedBytes>builder()
        .setBootstrapServers(bootstrapServers)
        .setRecordSerializer(new KeyedBytesSerializationSchema(topic))
        // At-least-once (RK-06, ADR-0004): the sink flushes on every checkpoint; duplicates after
        // a restart are expected and absorbed by the materializer's offset guard.
        .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
        .setProperty("enable.idempotence", "true")
        .setProperty("acks", "all")
        .build();
  }
}
