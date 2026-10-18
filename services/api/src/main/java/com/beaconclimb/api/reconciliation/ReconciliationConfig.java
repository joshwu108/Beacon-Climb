package com.beaconclimb.api.reconciliation;

import com.beaconclimb.api.dlq.DlqPublisher;
import com.beaconclimb.api.outbox.StreamProperties;
import com.beaconclimb.events.EventValidator;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;

/**
 * The stream-reconciliation.v1 consumer (BC-0309), wired only under {@code
 * beacon.stream.enabled=true} like every other Kafka bean and shaped like the materializer: one
 * {@link ConcurrentMessageListenerContainer} with concurrency 1, group {@code
 * beacon.stream.consumer.reconciliation-group-id}, {@code enable.auto.commit=false}, {@code
 * auto.offset.reset=earliest}, {@code MANUAL_IMMEDIATE} acknowledgements and unlimited
 * fixed-backoff retries. The repository, the metrics and {@link ReconcileService} are ordinary
 * beans: the {@code reconcile} profile needs them without a broker.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "beacon.stream", name = "enabled", havingValue = "true")
public class ReconciliationConfig {

  static final String CLIENT_ID = "beacon-api-reconciliation";

  @Bean
  ConsumerFactory<String, byte[]> reconciliationConsumerFactory(
      StreamProperties properties, MeterRegistry registry) {
    Map<String, Object> config = new HashMap<>();
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers());
    config.put(ConsumerConfig.CLIENT_ID_CONFIG, CLIENT_ID);
    config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.consumer().reconciliationGroupId());
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    config.put(
        ConsumerConfig.MAX_POLL_RECORDS_CONFIG, properties.reconciliation().maxPollRecords());
    DefaultKafkaConsumerFactory<String, byte[]> factory = new DefaultKafkaConsumerFactory<>(config);
    factory.addListener(new MicrometerConsumerListener<>(registry));
    return factory;
  }

  @Bean
  ReconciliationListener reconciliationListener(
      EventValidator validator,
      ReconciliationRepository repository,
      TransactionTemplate tx,
      DlqPublisher dlqPublisher,
      ReconciliationMetrics metrics,
      Clock clock) {
    return new ReconciliationListener(validator, repository, tx, dlqPublisher, metrics, clock);
  }

  @Bean
  ConcurrentMessageListenerContainer<String, byte[]> reconciliationContainer(
      @Qualifier("reconciliationConsumerFactory")
          ConsumerFactory<String, byte[]> reconciliationConsumerFactory,
      ReconciliationListener listener,
      StreamProperties properties) {
    ContainerProperties containerProperties =
        new ContainerProperties(properties.topics().reconciliation());
    containerProperties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    containerProperties.setMessageListener(listener);
    ConcurrentMessageListenerContainer<String, byte[]> container =
        new ConcurrentMessageListenerContainer<>(
            reconciliationConsumerFactory, containerProperties);
    container.setConcurrency(1);
    container.setBeanName("reconciliationContainer");
    container.setCommonErrorHandler(
        new DefaultErrorHandler(
            new FixedBackOff(
                properties.reconciliation().retryBackoff().toMillis(),
                FixedBackOff.UNLIMITED_ATTEMPTS)));
    return container;
  }
}
