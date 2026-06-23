package com.arthvritt.platform.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry in a {@link CommandResponse}'s {@code emitted_events} list (B4 §2.3): the audit envelope a
 * command appended, named by its {@code event_id} so the caller can subscribe to or query its downstream
 * consequences.
 */
public record EmittedEvent(
        @JsonProperty("event_id") UUID eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("occurred_at") Instant occurredAt) {
}
