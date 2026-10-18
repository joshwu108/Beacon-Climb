package com.beaconclimb.api.dlq;

import com.beaconclimb.api.outbox.StreamProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * The api-side DLQ writer (BC-0302), only under {@code beacon.stream.enabled=true} like every other
 * Kafka bean. It reuses the outbox producer ({@code OutboxConfig}: idempotent, acks=all, bounded
 * timeouts) because a DLQ write needs the same guarantees as an event publish.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "beacon.stream", name = "enabled", havingValue = "true")
public class DlqConfig {

  @Bean
  DlqPublisher dlqPublisher(
      KafkaTemplate<String, byte[]> outboxKafkaTemplate,
      StreamProperties properties,
      MeterRegistry registry) {
    return new DlqPublisher(outboxKafkaTemplate, properties, registry);
  }
}
