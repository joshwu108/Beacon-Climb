package com.beaconclimb.stream.features;

import com.beaconclimb.stream.AttemptEvent;
import com.beaconclimb.stream.KeyedBytes;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;

/** The operator under {@code KeyedOneInputStreamOperatorTestHarness}, keyed by user_id. */
final class Harness implements AutoCloseable {

  static final long PROCESSING_TIME = 1_760_000_000_000L;

  final KeyedOneInputStreamOperatorTestHarness<String, AttemptEvent, KeyedBytes> inner;

  private Harness(KeyedOneInputStreamOperatorTestHarness<String, AttemptEvent, KeyedBytes> inner) {
    this.inner = inner;
  }

  static Harness open() throws Exception {
    return open(null);
  }

  /** A fresh operator instance, restored from {@code snapshot} when one is given. */
  static Harness open(OperatorSubtaskState snapshot) throws Exception {
    KeyedOneInputStreamOperatorTestHarness<String, AttemptEvent, KeyedBytes> harness =
        new KeyedOneInputStreamOperatorTestHarness<>(
            new KeyedProcessOperator<>(new ClimberFeaturesFunction()),
            AttemptEvent::userId,
            Types.STRING);
    harness.setup();
    if (snapshot != null) {
      harness.initializeState(snapshot);
    }
    harness.open();
    harness.setProcessingTime(PROCESSING_TIME);
    return new Harness(harness);
  }

  void watermark(long millis) throws Exception {
    inner.processWatermark(millis);
  }

  void process(AttemptEvent event) throws Exception {
    inner.processElement(event, event.occurredAtMillis());
  }

  List<Emitted> emitted() {
    return Emitted.all(inner.extractOutputValues());
  }

  /** The harness returns null for a side output that never received a record. */
  List<KeyedBytes> late() {
    ConcurrentLinkedQueue<StreamRecord<KeyedBytes>> queue =
        inner.getSideOutput(ClimberFeaturesFunction.LATE);
    return queue == null ? List.of() : queue.stream().map(StreamRecord::getValue).toList();
  }

  /** Keyed state entries = users holding a (non-empty) attempt map, not attempts. */
  int usersWithState() {
    return inner.numKeyedStateEntries();
  }

  OperatorSubtaskState snapshot(long checkpointId) throws Exception {
    return inner.snapshot(checkpointId, PROCESSING_TIME);
  }

  @Override
  public void close() throws Exception {
    inner.close();
  }
}
