package com.arthvritt.platform.audit;

import com.arthvritt.platform.shared.Ids;

import java.time.Instant;
import java.util.UUID;

/**
 * Factory for the common {@link AuditEventEnvelope} spine, so every producer doesn't re-type the
 * {@code eventId}/{@code occurredAt}/{@code correlationId}/{@code aggregateVersion} boilerplate
 * (extracted per [[DL-BE-018]]/[[DL-BE-019]] once the fourth producer landed). {@link #seed} returns a
 * builder pre-filled with a fresh {@code eventId}, {@code occurredAt = now}, a fresh
 * {@code correlationId}, and {@code aggregateVersion = 1}; callers set the event type, actor, and
 * payload, and override any default (e.g. a shared {@code correlationId}, a real
 * {@code aggregateVersion}, or {@code commandId}/before/after for command envelopes).
 */
public final class AuditEnvelopes {

    private AuditEnvelopes() {
    }

    public static AuditEventEnvelope.Builder seed(String context, String aggregateType, UUID aggregateId) {
        return AuditEventEnvelope.builder()
                .eventId(Ids.newId())
                .occurredAt(Instant.now())
                .correlationId(Ids.newId())
                .aggregateVersion(1)
                .context(context)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId);
    }
}
