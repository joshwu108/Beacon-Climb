package com.beaconclimb.api.platform.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring for the HTTP platform slice: typed {@code beacon.http.*} properties. */
@Configuration
@EnableConfigurationProperties(HttpProperties.class)
class PlatformWebConfig {

  /** Parsed once at startup; a malformed entry fails the context (BC-0123 F3). */
  @Bean
  TrustedProxies trustedProxies(HttpProperties properties) {
    return TrustedProxies.parse(properties.trustedProxies());
  }
}
