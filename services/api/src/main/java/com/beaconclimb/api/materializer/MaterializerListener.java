package com.beaconclimb.api.materializer;

import com.beaconclimb.api.dlq.DlqPublisher;
import com.beaconclimb.api.materializer.MaterializerService.Result;
import com.beaconclimb.events.ErrorCode;
import com.beaconclimb.events.EventValidator;
import com.beaconclimb.events.ValidationResult;
import com.beaconclimb.events.ValidationResult.Invalid;
import com.beaconclimb.events.ValidationResult.Valid;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * The climber-features.v1 consumer (BC-0305). Per record, in order: validate the bytes with the
 * shared {@link EventValidator}; an invalid record goes to stream-dlq.v1 and is acknowledged. A
 * valid record is applied by {@link MaterializerService} in one transaction; a conflict goes to the
 * DLQ with {@link ErrorCode#CHECKSUM_CONFLICT} (the message says whether the checksum or the
 * partition differed); then the record is acknowledged ({@code MANUAL_IMMEDIATE}: a synchronous
 * offset commit).
 *
 * <p>Any exception before the acknowledgement propagates: the container's error handler seeks back
 * and redelivers the record after the configured backoff, without limit (at-least-once; the service
 * turns the redelivery of an already committed record into a duplicate). The one exception that is
 * not retried is a {@link DataIntegrityViolationException} from the write (a feature event for a
 * user the database does not know, say): retrying cannot fix it, so it goes to the DLQ as {@code
 * SCHEMA_VIOLATION} with the constraint name and is acknowledged.
 */
public class MaterializerListener implements AcknowledgingMessageListener<String, byte[]> {

  private static final Logger log = LoggerFactory.getLogger(MaterializerListener.class);

  private final EventValidator validator;
  private final MaterializerService service;
  private final DlqPublisher dlq;
  private final MaterializerMetrics metrics;
  private final MaterializerHook afterCommit;

  public MaterializerListener(
      EventValidator validator,
      MaterializerService service,
      DlqPublisher dlq,
      MaterializerMetrics metrics,
      MaterializerHook afterCommit) {
    this.validator = validator;
    this.service = service;
    this.dlq = dlq;
    this.metrics = metrics;
    this.afterCommit = afterCommit;
  }

  @Override
  public void onMessage(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
    try {
      handle(record);
      ack.acknowledge();
    } catch (RuntimeException e) {
      metrics.error();
      log.warn(
          "materializer outcome=ERROR topic={} partition={} offset={} key={} error={}",
          record.topic(),
          record.partition(),
          record.offset(),
          record.key(),
          e.toString());
      throw e;
    }
  }

  private void handle(ConsumerRecord<String, byte[]> record) {
    ValidationResult verdict = validator.validate(record.topic(), record.value());
    if (verdict instanceof Invalid invalid) {
      metrics.invalid(invalid.errorCode());
      dlq.publish(record, invalid);
      return;
    }
    FeatureEvent event = FeatureEvent.from(record.value(), ((Valid) verdict).payload());
    Result result;
    try {
      result = service.apply(record.partition(), record.offset(), event);
    } catch (DataIntegrityViolationException e) {
      metrics.invalid(ErrorCode.SCHEMA_VIOLATION);
      dlq.publish(
          record,
          ErrorCode.SCHEMA_VIOLATION,
          "row rejected by the database: "
              + rootMessage(e)
              + " (event_id "
              + event.eventId()
              + ")");
      return;
    }
    afterCommit.afterCommit(record, result);
    metrics.outcome(result.outcome());
    if (result.outcome().isConflict()) {
      log.warn(
          "materializer outcome={} event_id={} user_id={} partition={} offset={} detail={}",
          result.outcome(),
          event.eventId(),
          event.userId(),
          record.partition(),
          record.offset(),
          result.detail());
      dlq.publish(record, ErrorCode.CHECKSUM_CONFLICT, result.outcome() + ": " + result.detail());
      return;
    }
    log.debug(
        "materializer outcome={} event_id={} user_id={} partition={} offset={} cutoff_w={}",
        result.outcome(),
        event.eventId(),
        event.userId(),
        record.partition(),
        record.offset(),
        event.sourceWatermark());
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
