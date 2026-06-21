package com.arthvritt.platform.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

/**
 * Computes the tamper-evidence hash of an audit envelope: SHA-256 over the <b>RFC 8785 (JCS)</b>
 * canonical JSON of the {@link AuditRow} (all persisted columns except {@code envelope_hash}).
 *
 * <p>The encoding is <b>frozen</b> ({@link #HASH_ENCODING_VERSION}) — any change forks the chain.
 * JCS normalizes key order, whitespace, strings and numbers, so the same logical envelope hashes
 * identically whether built in-app (append) or re-read from JSONB (verify). Timestamps are truncated
 * to microseconds (Postgres {@code timestamptz} precision) so they round-trip exactly.
 */
@Component
class AuditCanonicalizer {

    /** Version of the canonical encoding; included in the hashed payload. Bumping it forks the chain. */
    static final int HASH_ENCODING_VERSION = 1;

    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_INSTANT;

    private final ObjectMapper mapper;

    AuditCanonicalizer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    byte[] hash(AuditRow r) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("event_id", r.eventId().toString());
        node.put("event_type", r.eventType());
        node.put("event_version", r.eventVersion());
        node.put("schema_uri", r.schemaUri());
        node.put("occurred_at", ts(r.occurredAt()));
        node.put("recorded_at", ts(r.recordedAt()));
        setJson(node, "actor", r.actor());
        node.put("aggregate_type", r.aggregateType());
        node.put("aggregate_id", r.aggregateId().toString());
        node.put("aggregate_version", r.aggregateVersion());
        node.put("correlation_id", r.correlationId().toString());
        node.put("causation_id", str(r.causationId()));
        node.put("command_id", str(r.commandId()));
        setJson(node, "payload", r.payload());
        setJson(node, "before_state", r.beforeState());
        setJson(node, "after_state", r.afterState());
        node.put("is_state_transition", r.stateTransition());
        node.put("corrects", str(r.corrects()));
        node.put("chain_shard", r.chainShard());
        node.put("previous_envelope_hash", hex(r.previousEnvelopeHash()));
        node.put("hash_encoding_version", HASH_ENCODING_VERSION);
        return sha256(canonicalize(node));
    }

    /** Microsecond-truncated ISO-8601; matches Postgres timestamptz precision for exact round-trip. */
    private static String ts(java.time.Instant instant) {
        return TS.format(instant.truncatedTo(ChronoUnit.MICROS));
    }

    /** Sets a JSON field, storing an explicit JSON null when the node is absent (consistent both ways). */
    private static void setJson(ObjectNode target, String name, JsonNode value) {
        target.set(name, value == null ? target.nullNode() : value);
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    static String hex(byte[] bytes) {
        return bytes == null ? null : HexFormat.of().formatHex(bytes);
    }

    private byte[] canonicalize(JsonNode node) {
        try {
            return new JsonCanonicalizer(mapper.writeValueAsString(node)).getEncodedUTF8();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to canonicalize audit envelope", e);
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // every JVM ships it
        }
    }
}
