package com.beaconclimb.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.attempts.AttemptsTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * BC-0301: with {@code beacon.stream.enabled} at its default (false) the context holds no Kafka or
 * outbox bean and no scheduled task, so {@code make dev} and the core suite never need a broker; an
 * outbox row written through the API stays unpublished.
 */
class OutboxDisabledIT extends AttemptsTestSupport {

  /** Three default poll intervals; the only way to show a scheduler is absent is to wait. */
  private static final long THREE_POLLS_MS = 1_500;

  @Autowired ApplicationContext context;

  @Test
  void noKafkaOutboxOrSchedulingBeansExist() {
    assertThat(context.getBeanNamesForType(OutboxPublisher.class)).isEmpty();
    assertThat(context.getBeanNamesForType(OutboxReconciliation.class)).isEmpty();
    assertThat(context.getBeanNamesForType(OutboxMetrics.class)).isEmpty();
    assertThat(context.getBeanNamesForType(StreamProperties.class)).isEmpty();
    assertThat(context.getBeanNamesForType(KafkaTemplate.class)).isEmpty();
    assertThat(context.getBeanNamesForType(ProducerFactory.class)).isEmpty();
    assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    assertThat(context.getBeansOfType(ScheduledTaskHolder.class).values())
        .allSatisfy(holder -> assertThat(holder.getScheduledTasks()).isEmpty());
  }

  @Test
  void outboxRowsStayUnpublishedWithoutThePublisher() throws InterruptedException {
    Account account = registerWithConsent();
    UUID attemptId = UUID.fromString(createAttempt(account, visibleRouteId()));

    Thread.sleep(THREE_POLLS_MS);

    assertThat(outboxRows(attemptId))
        .singleElement()
        .satisfies(row -> assertThat(row.get("published_at")).isNull());
  }
}
