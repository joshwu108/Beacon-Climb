package com.beaconclimb.api.attempts;

import com.beaconclimb.events.EventSchemas;
import com.beaconclimb.events.EventValidator;
import com.networknt.schema.Error;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The {@code attempts.v1} event contract as the attempt write path sees it (BC-0109): a thin
 * delegate to the shared {@link EventValidator} (BC-0302, services/events), so the producer and
 * every consumer judge conformance with ONE schema set. The error list keeps its shape: {@link
 * AttemptEvents} fails the attempt write with a server error when it is non-empty.
 */
@Component
public class AttemptEventSchema {

  private final EventValidator validator;

  /** For tests that need no Spring context; compiles the schemas once per instance. */
  public AttemptEventSchema() {
    this(new EventValidator());
  }

  @Autowired
  public AttemptEventSchema(EventValidator validator) {
    this.validator = validator;
  }

  /** Empty when the event conforms. */
  public List<Error> validate(JsonNode event) {
    return validator.errors(EventSchemas.ATTEMPTS, event);
  }
}
