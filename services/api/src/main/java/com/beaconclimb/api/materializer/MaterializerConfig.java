package com.beaconclimb.api.materializer;

import com.beaconclimb.api.dlq.DlqPublisher;
import com.beaconclimb.api.features.FeatureSnapshotRepository;
import com.beaconclimb.api.outbox.StreamProperties;
import com.beaconclimb.events.EventValidator;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.ObjectProvider;
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
 * The climber-features.v1 materializer (BC-0305), wired only under {@code
 * beacon.stream.enabled=true} like every other Kafka bean. One {@link
 * ConcurrentMessageListenerContainer} with concurrency 1 (six partitions, one consumer thread:
 * enough locally and it keeps the per-record transaction plus synchronous commit simple), group
 * {@code beacon.stream.consumer.materializer-group-id}, {@code enable.auto.commit=false}, {@code
 * auto.offset.reset=earliest}, {@code max.poll.records} from the properties, {@code
 * MANUAL_IMMEDIATE} acknowledgements. The error handler retries a failed record after {@code
 * retry-backoff} without limit: skipping would lose a snapshot silently.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "beacon.stream", name = "enabled", havingValue = "true")
public class MaterializerConfig {

  static final String CLIENT_ID = "beacon-api-materializer";

  @Bean
  ConsumerFactory<String, byte[]> materializerConsumerFactory(
      StreamProperties properties, MeterRegistry registry) {
    Map<String, Object> config = new HashMap<>();
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers());
    config.put(ConsumerConfig.CLIENT_ID_CONFIG, CLIENT_ID);
    config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.consumer().materializerGroupId());
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, properties.materializer().maxPollRecords());
    DefaultKafkaConsumerFactory<String, byte[]> factory = new DefaultKafkaConsumerFactory<>(config);
    factory.addListener(new MicrometerConsumerListener<>(registry));
    return factory;
  }

  @Bean
  MaterializerMetrics materializerMetrics(MeterRegistry registry) {
    return new MaterializerMetrics(registry);
  }

  @Bean
  MaterializerService materializerService(
      FeatureSnapshotRepository snapshots, TransactionTemplate tx, Clock clock) {
    return new MaterializerService(snapshots, tx, clock);
  }

  @Bean
  MaterializerListener materializerListener(
      EventValidator validator,
      MaterializerService service,
      DlqPublisher dlqPublisher,
      MaterializerMetrics metrics,
      ObjectProvider<MaterializerHook> hook) {
    return new MaterializerListener(
        validator,
        service,
        dlqPublisher,
        metrics,
        hook.getIfAvailable(() -> MaterializerHook.NONE));
  }

  @Bean
  ConcurrentMessageListenerContainer<String, byte[]> materializerContainer(
      ConsumerFactory<String, byte[]> materializerConsumerFactory,
      MaterializerListener listener,
      StreamProperties properties) {
    ContainerProperties containerProperties =
        new ContainerProperties(properties.topics().features());
    containerProperties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    containerProperties.setMessageListener(listener);
    ConcurrentMessageListenerContainer<String, byte[]> container =
        new ConcurrentMessageListenerContainer<>(materializerConsumerFactory, containerProperties);
    container.setConcurrency(1);
    container.setBeanName("materializerContainer");
    container.setCommonErrorHandler(
        new DefaultErrorHandler(
            new FixedBackOff(
                properties.materializer().retryBackoff().toMillis(),
                FixedBackOff.UNLIMITED_ATTEMPTS)));
    return container;
  }
}
