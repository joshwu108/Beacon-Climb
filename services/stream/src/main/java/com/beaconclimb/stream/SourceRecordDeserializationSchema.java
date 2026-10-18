package com.beaconclimb.stream;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/**
 * Turns a Kafka consumer record into a {@link SourceRecord} without interpreting the value: the
 * bytes are handed on as they are so a rejected record reaches the DLQ byte for byte (ADR-0004).
 */
public final class SourceRecordDeserializationSchema
    implements KafkaRecordDeserializationSchema<SourceRecord> {

  private static final long serialVersionUID = 1L;

  @Override
  public void deserialize(ConsumerRecord<byte[], byte[]> record, Collector<SourceRecord> out) {
    Map<String, String> headers = new LinkedHashMap<>();
    for (Header header : record.headers()) {
      if (header.value() != null) {
        headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
      }
    }
    String key = record.key() == null ? "" : new String(record.key(), StandardCharsets.UTF_8);
    byte[] value = record.value() == null ? new byte[0] : record.value();
    out.collect(new SourceRecord(key, value, record.partition(), record.offset(), headers));
  }

  @Override
  public TypeInformation<SourceRecord> getProducedType() {
    return TypeInformation.of(SourceRecord.class);
  }
}
