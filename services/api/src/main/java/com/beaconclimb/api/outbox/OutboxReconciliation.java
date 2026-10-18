package com.beaconclimb.api.outbox;

import java.util.List;

/**
 * Read-only view of what the publisher has not managed to deliver (BC-0301): the backlog summary
 * and the rows unpublished for longer than {@code beacon.stream.publisher.stale-after}. {@code make
 * outbox-status} is the psql twin of {@link #backlog()}. Nothing here mutates the outbox; a stale
 * row is retried by the next poll, never skipped.
 */
public class OutboxReconciliation {

  public static final int DEFAULT_LIMIT = 100;

  private final OutboxRepository repository;
  private final StreamProperties properties;

  public OutboxReconciliation(OutboxRepository repository, StreamProperties properties) {
    this.repository = repository;
    this.properties = properties;
  }

  public OutboxRepository.Backlog backlog() {
    return repository.backlog(properties.publisher().staleAfter());
  }

  public List<OutboxRepository.StaleRow> stale() {
    return stale(DEFAULT_LIMIT);
  }

  public List<OutboxRepository.StaleRow> stale(int limit) {
    return repository.stale(properties.publisher().staleAfter(), limit);
  }
}
