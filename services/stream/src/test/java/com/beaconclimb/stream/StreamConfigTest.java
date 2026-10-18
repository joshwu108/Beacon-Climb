package com.beaconclimb.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ExternalizedCheckpointRetention;
import org.apache.flink.configuration.RestartStrategyOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StreamConfigTest {

  @Test
  @DisplayName("defaults are the binding design-note values")
  void defaults() {
    StreamConfig config = StreamConfig.fromEnv(Map.of());

    assertThat(config.bootstrapServers()).isEqualTo("kafka:9092");
    assertThat(config.attemptsTopic()).isEqualTo("attempts.v1");
    assertThat(config.featuresTopic()).isEqualTo("climber-features.v1");
    assertThat(config.reconciliationTopic()).isEqualTo("stream-reconciliation.v1");
    assertThat(config.groupId()).isEqualTo("beacon-stream");
    assertThat(config.checkpointDir()).isEqualTo("file:///checkpoints");
    assertThat(config.checkpointInterval()).isEqualTo(Duration.ofSeconds(10));
    assertThat(config.parallelism()).isEqualTo(1);
  }

  @Test
  @DisplayName("environment overrides bootstrap servers, parallelism and checkpoint dir")
  void overrides() {
    StreamConfig config =
        StreamConfig.fromEnv(
            Map.of(
                StreamConfig.ENV_BOOTSTRAP, " 127.0.0.1:9094 ",
                StreamConfig.ENV_PARALLELISM, "2",
                StreamConfig.ENV_CHECKPOINT_DIR, "file:///tmp/ckpt"));

    assertThat(config.bootstrapServers()).isEqualTo("127.0.0.1:9094");
    assertThat(config.parallelism()).isEqualTo(2);
    assertThat(config.checkpointDir()).isEqualTo("file:///tmp/ckpt");
  }

  @Test
  @DisplayName("a non-positive or non-numeric parallelism fails fast with the variable name")
  void rejectsBadParallelism() {
    assertThatThrownBy(() -> StreamConfig.fromEnv(Map.of(StreamConfig.ENV_PARALLELISM, "0")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(StreamConfig.ENV_PARALLELISM);
    assertThatThrownBy(() -> StreamConfig.fromEnv(Map.of(StreamConfig.ENV_PARALLELISM, "two")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(StreamConfig.ENV_PARALLELISM);
  }

  @Test
  @DisplayName(
      "the Flink configuration carries 10 s filesystem checkpoints, retention and fixed-delay restarts")
  void flinkConfiguration() {
    Configuration flink = StreamJob.flinkConfiguration(StreamConfig.fromEnv(Map.of()));

    assertThat(flink.get(CheckpointingOptions.CHECKPOINTING_INTERVAL))
        .isEqualTo(Duration.ofSeconds(10));
    assertThat(flink.get(CheckpointingOptions.CHECKPOINT_STORAGE)).isEqualTo("filesystem");
    assertThat(flink.get(CheckpointingOptions.CHECKPOINTS_DIRECTORY))
        .isEqualTo("file:///checkpoints");
    assertThat(flink.get(CheckpointingOptions.EXTERNALIZED_CHECKPOINT_RETENTION))
        .isEqualTo(ExternalizedCheckpointRetention.RETAIN_ON_CANCELLATION);
    assertThat(flink.get(RestartStrategyOptions.RESTART_STRATEGY)).isEqualTo("fixed-delay");
    assertThat(flink.get(RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_ATTEMPTS))
        .isEqualTo(100);
    assertThat(flink.get(RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_DELAY))
        .isEqualTo(Duration.ofSeconds(5));
  }
}
