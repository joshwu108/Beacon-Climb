package com.beaconclimb.stream;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/**
 * Job configuration read from the environment (names mirror the api's {@code beacon.stream.*}
 * relaxed binding so one .env block drives both halves). Topic names, group id and the checkpoint
 * directory are the binding values from ADR-0004 / the streaming design note.
 */
public record StreamConfig(
    String bootstrapServers,
    String attemptsTopic,
    String featuresTopic,
    String reconciliationTopic,
    String dlqTopic,
    String groupId,
    String checkpointDir,
    Duration checkpointInterval,
    int parallelism,
    int restartAttempts,
    Duration restartDelay) {

  public static final String ENV_BOOTSTRAP = "BEACON_STREAM_BOOTSTRAPSERVERS";
  public static final String ENV_PARALLELISM = "BEACON_STREAM_PARALLELISM";
  public static final String ENV_CHECKPOINT_DIR = "BEACON_STREAM_CHECKPOINTDIR";
  public static final String ENV_RESTART_ATTEMPTS = "BEACON_STREAM_RESTARTATTEMPTS";

  static final String DEFAULT_BOOTSTRAP = "kafka:9092";
  static final String ATTEMPTS_TOPIC = "attempts.v1";
  static final String FEATURES_TOPIC = "climber-features.v1";
  static final String RECONCILIATION_TOPIC = "stream-reconciliation.v1";
  static final String DLQ_TOPIC = "stream-dlq.v1";
  static final String GROUP_ID = "beacon-stream";
  static final String DEFAULT_CHECKPOINT_DIR = "file:///checkpoints";
  static final Duration CHECKPOINT_INTERVAL = Duration.ofSeconds(10);
  static final int DEFAULT_PARALLELISM = 1;
  static final int DEFAULT_RESTART_ATTEMPTS = 100;
  static final Duration RESTART_DELAY = Duration.ofSeconds(5);

  public static StreamConfig fromEnv() {
    return fromEnv(System.getenv());
  }

  public static StreamConfig fromEnv(Map<String, String> env) {
    Function<String, String> read = key -> env.getOrDefault(key, "").trim();
    String bootstrap = read.apply(ENV_BOOTSTRAP);
    String checkpointDir = read.apply(ENV_CHECKPOINT_DIR);
    return new StreamConfig(
        bootstrap.isEmpty() ? DEFAULT_BOOTSTRAP : bootstrap,
        ATTEMPTS_TOPIC,
        FEATURES_TOPIC,
        RECONCILIATION_TOPIC,
        DLQ_TOPIC,
        GROUP_ID,
        checkpointDir.isEmpty() ? DEFAULT_CHECKPOINT_DIR : checkpointDir,
        CHECKPOINT_INTERVAL,
        positiveInt(ENV_PARALLELISM, read.apply(ENV_PARALLELISM), DEFAULT_PARALLELISM),
        positiveInt(
            ENV_RESTART_ATTEMPTS, read.apply(ENV_RESTART_ATTEMPTS), DEFAULT_RESTART_ATTEMPTS),
        RESTART_DELAY);
  }

  private static int positiveInt(String name, String raw, int fallback) {
    if (raw.isEmpty()) {
      return fallback;
    }
    int value;
    try {
      value = Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(name + " must be a positive integer, got '" + raw + "'");
    }
    if (value < 1) {
      throw new IllegalArgumentException(name + " must be a positive integer, got '" + raw + "'");
    }
    return value;
  }
}
