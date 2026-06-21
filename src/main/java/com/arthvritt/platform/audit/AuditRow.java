package com.arthvritt.platform.audit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * The exact set of persisted columns that go into the envelope hash (everything on
 * {@code sys_audit_event} except {@code envelope_hash} itself). Both the append path and the verifier
 * build an {@code AuditRow} and hand it to {@link AuditCanonicalizer}, so they hash identically — the
 * append path from the caller's envelope, the verifier from the stored row.
 *
 * <p>JSON columns are carried as {@link JsonNode} so canonicalization is independent of textual
 * formatting; {@code beforeState}/{@code afterState}/{@code causationId}/{@code commandId}/
 * {@code corrects}/{@code schemaUri}/{@code previousEnvelopeHash} may be null.
 */
record AuditRow(
        UUID eventId,
        String eventType,
        int eventVersion,
        String schemaUri,
        Instant occurredAt,
        Instant recordedAt,
        JsonNode actor,
        String aggregateType,
        UUID aggregateId,
        int aggregateVersion,
        UUID correlationId,
        UUID causationId,
        UUID commandId,
        JsonNode payload,
        JsonNode beforeState,
        JsonNode afterState,
        boolean stateTransition,
        UUID corrects,
        String chainShard,
        byte[] previousEnvelopeHash) {
}
