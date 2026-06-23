package com.arthvritt.platform.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

/**
 * The B4 §2.3 success body of a command: the affected aggregate's id + new version, the envelopes the
 * command emitted, and the correlation id. The whole shape is reconstructed from the appended audit
 * envelope(s) (see {@link CommandResponseAssembler}), so a replayed command (B4 §2.4) returns exactly the
 * original body.
 */
public record CommandResponse(
        @JsonProperty("aggregate_id") UUID aggregateId,
        @JsonProperty("aggregate_version") int aggregateVersion,
        @JsonProperty("emitted_events") List<EmittedEvent> emittedEvents,
        @JsonProperty("correlation_id") UUID correlationId) {
}
