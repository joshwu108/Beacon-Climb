package com.beaconclimb.api.materializer;

import com.beaconclimb.api.materializer.MaterializerService.Result;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * Called by {@link MaterializerListener} after the Postgres transaction of a record has committed
 * and before the record is acknowledged. Production has no bean of this type and uses {@link
 * #NONE}; the crash test registers one that throws once, which is exactly the window ADR-0004 names
 * ("materializer crash after commit"): the record is redelivered and must be detected as a
 * duplicate.
 */
@FunctionalInterface
public interface MaterializerHook {

  MaterializerHook NONE = (record, result) -> {};

  void afterCommit(ConsumerRecord<String, byte[]> record, Result result);
}
