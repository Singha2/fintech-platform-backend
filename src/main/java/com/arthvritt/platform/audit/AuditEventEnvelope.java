package com.arthvritt.platform.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Caller-supplied audit event (B2 §2.1 envelope). The append-assigned fields — {@code recordedAt},
 * {@code chainShard}, {@code previousEnvelopeHash}, {@code envelopeHash} — are NOT here; they are
 * computed by {@link AuditLog#append}. {@code context} is used only to derive the {@code chainShard}
 * and is not stored as its own column (it is recoverable from the shard).
 *
 * <p>{@code payload}/{@code beforeState}/{@code afterState} are JSON object maps; money fields inside
 * them use paise integers (the shared kernel {@code Money}). State-transition events must carry both
 * snapshots (enforced at the DB).
 */
public record AuditEventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        String schemaUri,
        Instant occurredAt,
        Actor actor,
        String context,
        String aggregateType,
        UUID aggregateId,
        int aggregateVersion,
        UUID correlationId,
        UUID causationId,
        UUID commandId,
        Map<String, Object> payload,
        Map<String, Object> beforeState,
        Map<String, Object> afterState,
        boolean stateTransition,
        UUID corrects) {

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder — {@code eventVersion} defaults to 1, {@code stateTransition} to false. */
    public static final class Builder {
        private UUID eventId;
        private String eventType;
        private int eventVersion = 1;
        private String schemaUri;
        private Instant occurredAt;
        private Actor actor;
        private String context;
        private String aggregateType;
        private UUID aggregateId;
        private int aggregateVersion;
        private UUID correlationId;
        private UUID causationId;
        private UUID commandId;
        private Map<String, Object> payload;
        private Map<String, Object> beforeState;
        private Map<String, Object> afterState;
        private boolean stateTransition;
        private UUID corrects;

        public Builder eventId(UUID v) { this.eventId = v; return this; }
        public Builder eventType(String v) { this.eventType = v; return this; }
        public Builder eventVersion(int v) { this.eventVersion = v; return this; }
        public Builder schemaUri(String v) { this.schemaUri = v; return this; }
        public Builder occurredAt(Instant v) { this.occurredAt = v; return this; }
        public Builder actor(Actor v) { this.actor = v; return this; }
        public Builder context(String v) { this.context = v; return this; }
        public Builder aggregateType(String v) { this.aggregateType = v; return this; }
        public Builder aggregateId(UUID v) { this.aggregateId = v; return this; }
        public Builder aggregateVersion(int v) { this.aggregateVersion = v; return this; }
        public Builder correlationId(UUID v) { this.correlationId = v; return this; }
        public Builder causationId(UUID v) { this.causationId = v; return this; }
        public Builder commandId(UUID v) { this.commandId = v; return this; }
        public Builder payload(Map<String, Object> v) { this.payload = v; return this; }
        public Builder beforeState(Map<String, Object> v) { this.beforeState = v; return this; }
        public Builder afterState(Map<String, Object> v) { this.afterState = v; return this; }
        public Builder stateTransition(boolean v) { this.stateTransition = v; return this; }
        public Builder corrects(UUID v) { this.corrects = v; return this; }

        public AuditEventEnvelope build() {
            return new AuditEventEnvelope(eventId, eventType, eventVersion, schemaUri, occurredAt,
                    actor, context, aggregateType, aggregateId, aggregateVersion, correlationId,
                    causationId, commandId, payload, beforeState, afterState, stateTransition, corrects);
        }
    }
}
