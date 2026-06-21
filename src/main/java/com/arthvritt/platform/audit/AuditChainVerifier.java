package com.arthvritt.platform.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Verifies a {@code chain_shard}'s tamper-evidence: every row's self-hash must recompute
 * ({@link AuditCanonicalizer}) and the per-shard chain must be unbroken and linear — exactly one
 * genesis row (NULL {@code previous_envelope_hash}), each successor linking to its predecessor's
 * hash, and no orphans. Read-only; the first failure is reported.
 */
@Service
public class AuditChainVerifier {

    private static final String SELECT_SHARD = """
            SELECT event_id, event_type, event_version, schema_uri, occurred_at, recorded_at, actor,
                   aggregate_type, aggregate_id, aggregate_version, correlation_id, causation_id,
                   command_id, payload, before_state, after_state, is_state_transition, corrects,
                   chain_shard, previous_envelope_hash, envelope_hash
            FROM sys_audit_event WHERE chain_shard = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditCanonicalizer canonicalizer;

    public AuditChainVerifier(JdbcTemplate jdbc, ObjectMapper mapper, AuditCanonicalizer canonicalizer) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.canonicalizer = canonicalizer;
    }

    public VerificationResult verify(String chainShard) {
        List<StoredEvent> events = jdbc.query(SELECT_SHARD, this::mapRow, chainShard);
        if (events.isEmpty()) {
            return VerificationResult.ok();
        }

        Map<String, StoredEvent> bySuccessor = new HashMap<>(); // hex(previous) -> row
        StoredEvent genesis = null;
        for (StoredEvent e : events) {
            byte[] previous = e.row().previousEnvelopeHash();
            if (previous == null) {
                if (genesis != null) {
                    return VerificationResult.broken(e.row().eventId(), "multiple genesis rows in shard");
                }
                genesis = e;
            } else {
                bySuccessor.put(AuditCanonicalizer.hex(previous), e);
            }
        }
        if (genesis == null) {
            return VerificationResult.broken(null, "no genesis row (none with null previous_envelope_hash)");
        }

        Set<UUID> visited = new HashSet<>();
        StoredEvent current = genesis;
        while (current != null) {
            if (!visited.add(current.row().eventId())) {
                return VerificationResult.broken(current.row().eventId(), "cycle detected in chain");
            }
            byte[] recomputed = canonicalizer.hash(current.row());
            if (!Arrays.equals(recomputed, current.storedHash())) {
                return VerificationResult.broken(current.row().eventId(), "envelope hash mismatch");
            }
            current = bySuccessor.get(AuditCanonicalizer.hex(current.storedHash()));
        }

        if (visited.size() != events.size()) {
            UUID orphan = events.stream()
                    .map(e -> e.row().eventId())
                    .filter(id -> !visited.contains(id))
                    .findFirst().orElse(null);
            return VerificationResult.broken(orphan, "row not linked into the chain (orphan or fork)");
        }
        return VerificationResult.ok();
    }

    private StoredEvent mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        AuditRow row = new AuditRow(
                rs.getObject("event_id", UUID.class),
                rs.getString("event_type"),
                rs.getInt("event_version"),
                rs.getString("schema_uri"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                jsonOf(rs.getString("actor")),
                rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getInt("aggregate_version"),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class),
                rs.getObject("command_id", UUID.class),
                jsonOf(rs.getString("payload")),
                jsonOf(rs.getString("before_state")),
                jsonOf(rs.getString("after_state")),
                rs.getBoolean("is_state_transition"),
                rs.getObject("corrects", UUID.class),
                rs.getString("chain_shard"),
                rs.getBytes("previous_envelope_hash"));
        return new StoredEvent(row, rs.getBytes("envelope_hash"));
    }

    private JsonNode jsonOf(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse stored audit JSON", e);
        }
    }

    private record StoredEvent(AuditRow row, byte[] storedHash) {
    }
}
