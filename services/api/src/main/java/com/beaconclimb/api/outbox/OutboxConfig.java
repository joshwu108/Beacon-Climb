package com.beaconclimb.api.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Everything Kafka in the api exists only under {@code beacon.stream.enabled=true} (BC-0301): this
 * configuration, its {@code beacon.stream.*} properties, the producer, the publisher and scheduling
 * itself. The build deliberately depends on {@code spring-kafka} alone (no Boot Kafka
 * auto-configuration module), so a disabled stream leaves no Kafka bean behind and {@code make dev}
 * plus the core test suite run without a broker.
 *
 * <p>Producer: idempotent, {@code acks=all}, five in-flight requests, {@code delivery.timeout.ms}
 * and {@code request.timeout.ms}/{@code max.block.ms} from the properties so a dead broker fails a
 * batch in seconds instead of the client default minute.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "beacon.stream", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(StreamProperties.class)
@EnableScheduling
public class OutboxConfig {

  static final String CLIENT_ID = "beacon-api-outbox";

  @Bean
  ProducerFactory<String, byte[]> outboxProducerFactory(StreamProperties properties) {
    Map<String, Object> config = new HashMap<>();
    config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers());
    config.put(ProducerConfig.CLIENT_ID_CONFIG, CLIENT_ID);
    config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    config.put(ProducerConfig.ACKS_CONFIG, "all");
    config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
    config.put(
        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
        (int) properties.publisher().deliveryTimeout().toMillis());
    config.put(
        ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
        (int) properties.publisher().requestTimeout().toMillis());
    config.put(
        ProducerConfig.MAX_BLOCK_MS_CONFIG, properties.publisher().requestTimeout().toMillis());
    config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
    return new DefaultKafkaProducerFactory<>(config);
  }

  @Bean
  KafkaTemplate<String, byte[]> outboxKafkaTemplate(ProducerFactory<String, byte[]> factory) {
    return new KafkaTemplate<>(factory);
  }

  @Bean
  OutboxRepository outboxRepository(JdbcClient jdbc) {
    return new OutboxRepository(jdbc);
  }

  @Bean
  OutboxMetrics outboxMetrics(MeterRegistry registry) {
    return new OutboxMetrics(registry);
  }

  @Bean
  OutboxReconciliation outboxReconciliation(
      OutboxRepository repository, StreamProperties properties) {
    return new OutboxReconciliation(repository, properties);
  }

  @Bean
  OutboxPublisher outboxPublisher(
      OutboxRepository repository,
      KafkaTemplate<String, byte[]> kafka,
      StreamProperties properties,
      OutboxMetrics metrics,
      TransactionTemplate tx) {
    return new OutboxPublisher(repository, kafka, properties, metrics, tx);
  }
}
