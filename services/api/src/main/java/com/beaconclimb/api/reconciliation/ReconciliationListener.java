package com.beaconclimb.api.reconciliation;

import com.beaconclimb.api.dlq.DlqPublisher;
import com.beaconclimb.events.ErrorCode;
import com.beaconclimb.events.EventSchemas;
import com.beaconclimb.events.EventValidator;
import com.beaconclimb.events.ValidationResult;
import com.beaconclimb.events.ValidationResult.Invalid;
import com.beaconclimb.events.ValidationResult.Valid;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The stream-reconciliation.v1 consumer (BC-0309). The record value is the ORIGINAL attempts.v1
 * bytes the stream job could not apply (more than 24 h behind its watermark), so it is validated
 * against the attempts.v1 contract with the shared {@link EventValidator}; an invalid payload goes
 * to stream-dlq.v1 with {@code dlq.consumer=reconciliation} and is acknowledged. A valid payload is
 * inserted into {@code reconciliation_events} in one transaction, {@code ON CONFLICT (event_id) DO
 * NOTHING} (a redelivery is counted as a duplicate and changes nothing), then the record is
 * acknowledged ({@code MANUAL_IMMEDIATE}: a synchronous offset commit).
 *
 * <p>Any exception before the acknowledgement propagates: the container's error handler seeks back
 * and redelivers the record after the configured backoff, without limit (at-least-once; the unique
 * {@code event_id} turns the redelivery of an already committed row into a duplicate). The one
 * exception that is not retried is a {@link DataIntegrityViolationException} from the insert (a
 * late event for a user the database does not know, say): retrying cannot fix it, so it goes to the
 * DLQ as {@code SCHEMA_VIOLATION} with the constraint name and is acknowledged.
 */
public class ReconciliationListener implements AcknowledgingMessageListener<String, byte[]> {

  public static final String DLQ_CONSUMER = "reconciliation";
  private static final Logger log = LoggerFactory.getLogger(ReconciliationListener.class);

  private final EventValidator validator;
  private final ReconciliationRepository repository;
  private final TransactionTemplate tx;
  private final DlqPublisher dlq;
  private final ReconciliationMetrics metrics;
  private final Clock clock;

  public ReconciliationListener(
      EventValidator validator,
      ReconciliationRepository repository,
      TransactionTemplate tx,
      DlqPublisher dlq,
      ReconciliationMetrics metrics,
      Clock clock) {
    this.validator = validator;
    this.repository = repository;
    this.tx = tx;
    this.dlq = dlq;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public void onMessage(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
    try {
      handle(record);
      ack.acknowledge();
    } catch (RuntimeException e) {
      log.warn(
          "reconciliation outcome=ERROR topic={} partition={} offset={} key={} error={}",
          record.topic(),
          record.partition(),
          record.offset(),
          record.key(),
          e.toString());
      throw e;
    }
  }

  private void handle(ConsumerRecord<String, byte[]> record) {
    ValidationResult verdict = validator.validate(EventSchemas.ATTEMPTS, record.value());
    if (verdict instanceof Invalid invalid) {
      metrics.invalid(invalid.errorCode());
      dlq.publish(record, invalid, DLQ_CONSUMER);
      return;
    }
    ReconciliationEvent event = ReconciliationEvent.from(record, ((Valid) verdict).payload());
    Boolean inserted;
    try {
      inserted =
          tx.execute(
              status -> repository.insert(event, clock.instant().truncatedTo(ChronoUnit.MILLIS)));
    } catch (DataIntegrityViolationException e) {
      metrics.invalid(ErrorCode.SCHEMA_VIOLATION);
      dlq.publish(
          record,
          ErrorCode.SCHEMA_VIOLATION,
          "row rejected by the database: " + rootMessage(e) + " (event_id " + event.eventId() + ")",
          DLQ_CONSUMER);
      return;
    }
    if (Boolean.TRUE.equals(inserted)) {
      metrics.received();
      metrics.refreshOpenRows();
      log.info(
          "reconciliation outcome=RECEIVED event_id={} user_id={} attempt_id={} revision={}"
              + " occurred_at={} watermark={} reason={} partition={} offset={}",
          event.eventId(),
          event.userId(),
          event.attemptId(),
          event.attemptRevision(),
          event.occurredAt(),
          event.sourceWatermark(),
          event.reason(),
          record.partition(),
          record.offset());
      return;
    }
    metrics.duplicate();
    log.debug(
        "reconciliation outcome=DUPLICATE event_id={} user_id={} partition={} offset={}",
        event.eventId(),
        event.userId(),
        record.partition(),
        record.offset());
  }

  private static String rootMessage(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String message = root.getMessage();
    if (message == null) {
      return root.getClass().getSimpleName();
    }
    int newline = message.indexOf('\n');
    return newline < 0 ? message : message.substring(0, newline);
  }
}
