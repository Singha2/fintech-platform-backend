package com.arthvritt.platform.audit;

import com.arthvritt.platform.shared.error.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * BC14 Audit Log — the single, serialized write path to {@code sys_audit_event} (non-negotiable #5).
 * Nothing else may insert into that table.
 *
 * <p>append() resolves the current head of the envelope's {@code chain_shard}, computes the
 * tamper-evidence hash ({@link AuditCanonicalizer}), and inserts. Chain linearity is guaranteed
 * declaratively by {@code uidx_audit_chain_link (chain_shard, previous_envelope_hash) NULLS NOT
 * DISTINCT}: a concurrent append chaining off the same head hits {@code ON CONFLICT DO NOTHING}
 * (zero rows), so we re-resolve the new head and retry — optimistic, no procedural lock
 * ([[DL-BE-002]], [[DL-BE-015]]). Runs in the caller's transaction, so a command's 2xx genuinely
 * means the fact is logged (X13/P8).
 */
@Service
public class AuditLog {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata"); // IST
    private static final int MAX_CHAIN_RETRIES = 50;

    private static final String INSERT_SQL = """
            INSERT INTO sys_audit_event (
                event_id, event_type, event_version, schema_uri, occurred_at, recorded_at, actor,
                aggregate_type, aggregate_id, aggregate_version, correlation_id, causation_id,
                command_id, payload, before_state, after_state, is_state_transition, corrects,
                chain_shard, previous_envelope_hash, envelope_hash)
            VALUES (?, ?, ?, ?::text, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::uuid, ?::uuid, ?::jsonb,
                ?::jsonb, ?::jsonb, ?, ?::uuid, ?, ?::bytea, ?)
            ON CONFLICT (chain_shard, previous_envelope_hash) DO NOTHING
            """;

    private static final String HEAD_SQL = """
            SELECT s.envelope_hash FROM sys_audit_event s
            WHERE s.chain_shard = ?
              AND NOT EXISTS (SELECT 1 FROM sys_audit_event c
                              WHERE c.chain_shard = s.chain_shard
                                AND c.previous_envelope_hash = s.envelope_hash)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditCanonicalizer canonicalizer;

    public AuditLog(JdbcTemplate jdbc, ObjectMapper mapper, AuditCanonicalizer canonicalizer) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.canonicalizer = canonicalizer;
    }

    @Transactional
    public AppendedEvent append(AuditEventEnvelope e) {
        validate(e); // fail with a clear domain error, not an NPE deep in the write path
        String chainShard = e.context() + ":" + LocalDate.now(BUSINESS_ZONE);
        Instant recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant occurredAt = e.occurredAt().truncatedTo(ChronoUnit.MICROS);
        JsonNode actor = mapper.valueToTree(actorMap(e.actor()));
        JsonNode payload = toJson(e.payload());
        JsonNode before = toJson(e.beforeState());
        JsonNode after = toJson(e.afterState());

        for (int attempt = 0; attempt < MAX_CHAIN_RETRIES; attempt++) {
            byte[] previousHash = currentHead(chainShard);
            AuditRow row = new AuditRow(e.eventId(), e.eventType(), e.eventVersion(), e.schemaUri(),
                    occurredAt, recordedAt, actor, e.aggregateType(), e.aggregateId(),
                    e.aggregateVersion(), e.correlationId(), e.causationId(), e.commandId(),
                    payload, before, after, e.stateTransition(), e.corrects(), chainShard, previousHash);
            byte[] envelopeHash = canonicalizer.hash(row);

            int inserted = insert(row, envelopeHash);
            if (inserted == 1) {
                return new AppendedEvent(e.eventId(), recordedAt, chainShard, previousHash, envelopeHash);
            }
            // ON CONFLICT DO NOTHING returned 0: a concurrent append took this slot — retry on the new head.
        }
        throw new IllegalStateException(
                "Audit append exceeded " + MAX_CHAIN_RETRIES + " chain conflicts on shard " + chainShard);
    }

    /**
     * Asserts the required envelope fields before any dereference or DB write. The snapshot rule for
     * state-transition events is intentionally left to the DB CHECK (the last line of defence).
     */
    private static void validate(AuditEventEnvelope e) {
        require(e.eventId() != null, "eventId is required");
        require(notBlank(e.eventType()), "eventType is required");
        require(e.occurredAt() != null, "occurredAt is required");
        require(e.actor() != null, "actor is required");
        require(e.actor() != null && notBlank(e.actor().actorType()), "actor.actorType is required");
        require(e.actor() != null && notBlank(e.actor().actorId()), "actor.actorId is required");
        require(notBlank(e.context()), "context is required");
        require(notBlank(e.aggregateType()), "aggregateType is required");
        require(e.aggregateId() != null, "aggregateId is required");
        require(e.aggregateVersion() >= 1, "aggregateVersion must be >= 1");
        require(e.eventVersion() >= 1, "eventVersion must be >= 1");
        require(e.correlationId() != null, "correlationId is required");
        require(e.payload() != null, "payload is required");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ValidationException("invalid audit envelope: " + message);
        }
    }

    private byte[] currentHead(String chainShard) {
        return jdbc.query(HEAD_SQL, rs -> rs.next() ? rs.getBytes(1) : null, chainShard);
    }

    private int insert(AuditRow r, byte[] envelopeHash) {
        return jdbc.update(INSERT_SQL,
                r.eventId(), r.eventType(), r.eventVersion(), r.schemaUri(),
                odt(r.occurredAt()), odt(r.recordedAt()), json(r.actor()),
                r.aggregateType(), r.aggregateId(), r.aggregateVersion(), r.correlationId(),
                r.causationId(), r.commandId(), json(r.payload()), json(r.beforeState()),
                json(r.afterState()), r.stateTransition(), r.corrects(),
                r.chainShard(), r.previousEnvelopeHash(), envelopeHash);
    }

    private static OffsetDateTime odt(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private JsonNode toJson(Map<String, Object> map) {
        return map == null ? null : mapper.valueToTree(map);
    }

    private String json(JsonNode node) {
        try {
            return node == null ? null : mapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize audit JSON column", ex);
        }
    }

    private static Map<String, Object> actorMap(Actor a) {
        // All three keys must be present for the DB actor-keys CHECK (values may be null).
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("actor_type", a.actorType());
        m.put("actor_id", a.actorId());
        m.put("session_id", a.sessionId());
        m.put("mfa_assertion_id", a.mfaAssertionId() == null ? null : a.mfaAssertionId().toString());
        m.put("agency_consent_id", a.agencyConsentId() == null ? null : a.agencyConsentId().toString());
        return m;
    }
}
