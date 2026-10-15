package com.beaconclimb.api.attempts.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** {@code components.schemas.AttemptList}: one page, newest first, and the keyset cursor. */
public record AttemptListView(
    List<AttemptView> items, @JsonProperty("next_cursor") String nextCursor) {

  public AttemptListView {
    items = List.copyOf(items);
  }

  @Override
  public String toString() {
    return "AttemptListView[items=" + items.size() + ", hasNext=" + (nextCursor != null) + "]";
  }
}
