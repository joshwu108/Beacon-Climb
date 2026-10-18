package com.beaconclimb.stream;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * Writes a {@link KeyedBytes} to one topic with its key as the record key (user_id for features and
 * reconciliation so every record of a user lands on the same partition, ADR-0004; the original key
 * for DLQ records) and the headers copied over; the partition is left to the producer's default key
 * hashing.
 */
public final class KeyedBytesSerializationSchema
    implements KafkaRecordSerializationSchema<KeyedBytes> {

  private static final long serialVersionUID = 1L;

  private final String topic;

  public KeyedBytesSerializationSchema(String topic) {
    this.topic = topic;
  }

  @Override
  public ProducerRecord<byte[], byte[]> serialize(
      KeyedBytes element, KafkaSinkContext context, Long timestamp) {
    ProducerRecord<byte[], byte[]> record =
        new ProducerRecord<>(topic, element.key.getBytes(StandardCharsets.UTF_8), element.value);
    if (element.headers != null) {
      for (Map.Entry<String, String> header : element.headers.entrySet()) {
        record.headers().add(header.getKey(), header.getValue().getBytes(StandardCharsets.UTF_8));
      }
    }
    return record;
  }
}
