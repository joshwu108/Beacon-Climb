package com.beaconclimb.api.materializer;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.attempts.AttemptsTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * BC-0305 (h): with {@code beacon.stream.enabled} at its default (false) the context holds no
 * materializer bean, no consumer factory and no listener container, so the core suite and {@code
 * make dev} never open a Kafka consumer.
 */
class MaterializerDisabledIT extends AttemptsTestSupport {

  @Autowired ApplicationContext context;

  @Test
  void noMaterializerBeansExist() {
    assertThat(context.getBeanNamesForType(MaterializerListener.class)).isEmpty();
    assertThat(context.getBeanNamesForType(MaterializerService.class)).isEmpty();
    assertThat(context.getBeanNamesForType(MaterializerMetrics.class)).isEmpty();
    assertThat(context.getBeanNamesForType(ConsumerFactory.class)).isEmpty();
    assertThat(context.getBeanNamesForType(MessageListenerContainer.class)).isEmpty();
  }
}
