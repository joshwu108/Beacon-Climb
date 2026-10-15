package com.beaconclimb.api.recommendations;

import java.nio.file.Path;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the local filesystem snapshot store from {@link DecisionsProperties}. */
@Configuration
@EnableConfigurationProperties(DecisionsProperties.class)
class RecommendationsConfig {

  @Bean
  SnapshotStore snapshotStore(DecisionsProperties properties) {
    return new LocalFsSnapshotStore(Path.of(properties.dir()), properties.maxFiles());
  }
}
