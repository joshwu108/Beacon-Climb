package com.beaconclimb.api.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.attempts.AttemptsTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * BC-0309 (d): with {@code beacon.stream.enabled} at its default (false) the context holds no
 * reconciliation listener, consumer factory or container, so the core suite and {@code make dev}
 * never open a Kafka consumer. The repository, the metrics and {@link ReconcileService} stay: the
 * {@code reconcile} profile repairs the table without a broker.
 */
class ReconciliationDisabledIT extends AttemptsTestSupport {

  @Autowired ApplicationContext context;

  @Test
  void noReconciliationConsumerBeansExist() {
    assertThat(context.getBeanNamesForType(ReconciliationListener.class)).isEmpty();
    assertThat(context.getBeanNamesForType(ConsumerFactory.class)).isEmpty();
    assertThat(context.getBeanNamesForType(MessageListenerContainer.class)).isEmpty();
    assertThat(context.containsBean("reconciliationContainer")).isFalse();
    assertThat(context.getBeanNamesForType(ReconcileService.class)).hasSize(1);
    assertThat(context.getBeanNamesForType(ReconciliationRepository.class)).hasSize(1);
  }
}
