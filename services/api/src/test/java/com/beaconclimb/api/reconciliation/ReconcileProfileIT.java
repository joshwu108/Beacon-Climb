package com.beaconclimb.api.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * BC-0309: the {@code reconcile} profile ({@code make reconcile}) boots without a web server and
 * without Kafka, runs {@link ReconcileRunner} once on startup over whatever is open in {@code
 * reconciliation_events} (rows other test classes left behind in the shared database included) and
 * ends with exit code 0 when every user it touched was repaired.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"spring.profiles.active=reconcile"})
class ReconcileProfileIT extends AbstractPostgresIT {

  @Autowired ApplicationContext context;
  @Autowired Environment environment;
  @Autowired ReconcileRunner runner;
  @Autowired ReconciliationRepository repository;

  @Test
  void reconcileProfileHasNoWebServerNoKafkaAndRanTheRepairOnce() {
    assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
    assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("none");
    assertThat(context.getBeanNamesForType(MessageListenerContainer.class)).isEmpty();
    assertThat(context.getBeanNamesForType(ReconciliationListener.class)).isEmpty();
    assertThat(runner.getExitCode()).isZero();
    assertThat(repository.openUserIds()).isEmpty();
  }
}
