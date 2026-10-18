package com.beaconclimb.stream;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * One attempts.v1 Kafka record as the job receives it: key, value bytes, partition, offset and
 * string headers. Everything the DLQ record needs to point back at the source (BC-0302,
 * contracts/events/README.md "DLQ headers") travels with the bytes, so an invalid record can be
 * quarantined with its coordinates and a redriven one can announce its attempt number.
 *
 * @param key the Kafka key as UTF-8, "" when the key was null
 * @param value the value bytes, untouched
 * @param partition source partition, -1 for a non-Kafka source (tests)
 * @param offset source offset, -1 for a non-Kafka source (tests)
 * @param headers the record headers decoded as UTF-8 (the contract headers are all text)
 */
public record SourceRecord(
    String key, byte[] value, int partition, long offset, Map<String, String> headers)
    implements Serializable {

  public SourceRecord {
    key = key == null ? "" : key;
    Objects.requireNonNull(value, "value");
    value = value.clone();
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }

  public static SourceRecord of(String key, byte[] value) {
    return new SourceRecord(key, value, -1, -1L, Map.of());
  }

  @Override
  public byte[] value() {
    return value.clone();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof SourceRecord other
        && key.equals(other.key)
        && Arrays.equals(value, other.value)
        && partition == other.partition
        && offset == other.offset
        && headers.equals(other.headers);
  }

  @Override
  public int hashCode() {
    return Objects.hash(key, Arrays.hashCode(value), partition, offset, headers);
  }

  @Override
  public String toString() {
    return "SourceRecord[key="
        + key
        + ", bytes="
        + value.length
        + ", partition="
        + partition
        + ", offset="
        + offset
        + "]";
  }
}
