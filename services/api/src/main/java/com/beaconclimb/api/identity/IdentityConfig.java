package com.beaconclimb.api.identity;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring for the identity slice: typed properties and an injectable UTC clock. */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
class IdentityConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
