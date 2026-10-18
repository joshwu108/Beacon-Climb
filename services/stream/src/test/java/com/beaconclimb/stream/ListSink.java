package com.beaconclimb.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.flink.api.connector.sink2.Sink;
import org.apache.flink.api.connector.sink2.SinkWriter;
import org.apache.flink.api.connector.sink2.WriterInitContext;

/**
 * Collects what the MiniCluster (same JVM) writes, per sink name. Stands in for the KafkaSinks in
 * {@link StreamJobMiniClusterTest}.
 */
final class ListSink<T> implements Sink<T> {

  private static final long serialVersionUID = 1L;
  private static final Map<String, Queue<Object>> COLLECTED = new ConcurrentHashMap<>();

  private final String name;

  ListSink(String name) {
    this.name = name;
    COLLECTED.put(name, new ConcurrentLinkedQueue<>());
  }

  @SuppressWarnings("unchecked")
  static <T> List<T> collected(String name) {
    return new ArrayList<>((Queue<T>) COLLECTED.getOrDefault(name, new ConcurrentLinkedQueue<>()));
  }

  @Override
  public SinkWriter<T> createWriter(WriterInitContext context) {
    Queue<Object> target = COLLECTED.get(name);
    return new SinkWriter<>() {
      @Override
      public void write(T element, Context context) {
        target.add(element);
      }

      @Override
      public void flush(boolean endOfInput) {}

      @Override
      public void close() {}
    };
  }
}
