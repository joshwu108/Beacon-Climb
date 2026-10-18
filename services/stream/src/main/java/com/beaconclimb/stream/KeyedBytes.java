package com.beaconclimb.stream;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A Kafka-bound record: the partition key (always {@code user_id}, ADR-0004), the value bytes and
 * optional string headers. A mutable POJO on purpose so Flink serialises it with the
 * PojoSerializer; nothing outside Flink's operator chain holds one.
 */
public class KeyedBytes {

  public String key;
  public byte[] value;
  public Map<String, String> headers;

  public KeyedBytes() {}

  public KeyedBytes(String key, byte[] value, Map<String, String> headers) {
    this.key = key;
    this.value = value;
    this.headers = new HashMap<>(headers);
  }

  public static KeyedBytes of(String key, byte[] value) {
    return new KeyedBytes(key, value, Map.of());
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof KeyedBytes other
        && Objects.equals(key, other.key)
        && Arrays.equals(value, other.value)
        && Objects.equals(headers, other.headers);
  }

  @Override
  public int hashCode() {
    return Objects.hash(key, Arrays.hashCode(value), headers);
  }

  @Override
  public String toString() {
    return "KeyedBytes[key=" + key + ", bytes=" + (value == null ? 0 : value.length) + "]";
  }
}
