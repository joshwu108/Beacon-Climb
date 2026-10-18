package com.beaconclimb.api.events;

import com.beaconclimb.events.EventValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One {@link EventValidator} per api process (BC-0302): the attempt write path ({@code
 * AttemptEventSchema}), the DLQ publisher and the materializer (BC-0305) all share it. Not
 * conditional on the stream flag because the outbox payload validation runs in core too.
 */
@Configuration(proxyBeanMethods = false)
public class EventContractConfig {

  @Bean
  EventValidator eventValidator() {
    return new EventValidator();
  }
}
