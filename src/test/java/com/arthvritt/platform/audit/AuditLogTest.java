package com.arthvritt.platform.audit;

import com.arthvritt.platform.AbstractIntegrationTest;
import com.arthvritt.platform.shared.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M2 invariant tests for the audit log (see docs/modules/M2-audit-log.md §7). Integration —
 * exercises the real hash chain, the immutability trigger, and concurrent appends on Testcontainers.
 * Each test uses a unique {@code context} so its {@code chain_shard} is isolated (audit rows are
 * append-only and cannot be cleaned up — by design).
 */
class AuditLogTest extends AbstractIntegrationTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired
    private AuditLog auditLog;

    @Autowired
    private AuditChainVerifier verifier;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void append_starts_a_chain() { // INV-2 genesis + INV-3 hash present
        String ctx = uniqueContext();
        AppendedEvent a = auditLog.append(envelope(ctx, 1));

        assertThat(a.envelopeHash()).hasSize(32);
        assertThat(a.previousEnvelopeHash()).isNull(); // first row in a shard
        assertThat(a.chainShard()).isEqualTo(ctx + ":" + LocalDate.now(IST));
        assertThat(verifier.verify(a.chainShard()).intact()).isTrue();
    }

    @Test
    void appends_link_into_a_verifiable_chain() { // INV-2 + INV-3
        String ctx = uniqueContext();
        AppendedEvent a1 = auditLog.append(envelope(ctx, 1));
        AppendedEvent a2 = auditLog.append(envelope(ctx, 2));
        AppendedEvent a3 = auditLog.append(envelope(ctx, 3));

        assertThat(a2.previousEnvelopeHash()).isEqualTo(a1.envelopeHash());
        assertThat(a3.previousEnvelopeHash()).isEqualTo(a2.envelopeHash());
        assertThat(verifier.verify(a1.chainShard()).intact()).isTrue();
    }

    @Test
    void audit_rows_are_immutable() { // INV-1 — DB trigger blocks UPDATE/DELETE
        AppendedEvent a = auditLog.append(envelope(uniqueContext(), 1));
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE sys_audit_event SET event_type = 'tampered' WHERE event_id = ?", a.eventId()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM sys_audit_event WHERE event_id = ?", a.eventId()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void verifier_detects_a_tampered_row() { // INV-3 — recomputed hash no longer matches
        String ctx = uniqueContext();
        AppendedEvent a1 = auditLog.append(envelope(ctx, 1));

        // Raw-insert a structurally-linked successor whose stored envelope_hash is bogus.
        UUID badId = Ids.newId();
        byte[] bogus = new byte[32];
        Arrays.fill(bogus, (byte) 0xEE);
        jdbc.update(
                "INSERT INTO sys_audit_event (event_id, event_type, event_version, occurred_at, "
                        + "recorded_at, actor, aggregate_type, aggregate_id, aggregate_version, "
                        + "correlation_id, payload, is_state_transition, chain_shard, "
                        + "previous_envelope_hash, envelope_hash) VALUES (?,?,1,now(),now(),?::jsonb,"
                        + "?,?,1,?,?::jsonb,false,?,?,?)",
                badId, ctx + ".Thing.Tampered",
                "{\"actor_type\":\"x\",\"actor_id\":\"y\",\"session_id\":null}",
                "Thing", UUID.randomUUID(), UUID.randomUUID(), "{}",
                a1.chainShard(), a1.envelopeHash(), bogus);

        VerificationResult r = verifier.verify(a1.chainShard());
        assertThat(r.intact()).isFalse();
        assertThat(r.firstBrokenEventId()).isEqualTo(badId);
    }

    @Test
    void concurrent_appends_to_one_shard_stay_linear() throws Exception { // INV-4
        String ctx = uniqueContext();
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<AppendedEvent>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int version = i + 1;
                futures.add(pool.submit(() -> auditLog.append(envelope(ctx, version))));
            }
            for (Future<AppendedEvent> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }

        String shard = ctx + ":" + LocalDate.now(IST);
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE chain_shard = ?", Integer.class, shard);
        assertThat(count).isEqualTo(n);
        assertThat(verifier.verify(shard).intact()).isTrue(); // no fork, all linked
    }

    @Test
    void chain_verifies_across_decimal_unicode_and_large_number_payloads() { // INV-3 round-trip fidelity
        // Guards against canonical-hash drift between the in-app hash (append) and the JSONB
        // round-trip (verify) for non-integer / large numbers, unicode, and nested structures.
        String ctx = uniqueContext();
        auditLog.append(baseBuilder(ctx, 1)
                .payload(Map.of(
                        "rate", new java.math.BigDecimal("12.50"),
                        "frac", 0.1,
                        "big", new java.math.BigInteger("100000000000000000000"),
                        "unicode", "naïve ☃ \"quoted\" \\slash",
                        "nested", Map.of("a", 1, "b", List.of(1, 2, 3))))
                .build());
        auditLog.append(baseBuilder(ctx, 2)
                .payload(Map.of("v", 5.00))
                .beforeState(Map.of("status", "x"))
                .afterState(Map.of("status", "y"))
                .stateTransition(true)
                .build());

        assertThat(verifier.verify(ctx + ":" + LocalDate.now(IST)).intact()).isTrue();
    }

    @Test
    void state_transition_without_snapshots_is_rejected() { // INV-6 — DB CHECK
        AuditEventEnvelope bad = baseBuilder(uniqueContext(), 1)
                .stateTransition(true) // but no before/after state
                .build();
        assertThatThrownBy(() -> auditLog.append(bad)).isInstanceOf(DataAccessException.class);
    }

    // --- helpers ---

    private static String uniqueContext() {
        return "test" + UUID.randomUUID().toString().replace("-", "");
    }

    private AuditEventEnvelope envelope(String context, int aggregateVersion) {
        return baseBuilder(context, aggregateVersion).build();
    }

    private AuditEventEnvelope.Builder baseBuilder(String context, int aggregateVersion) {
        return AuditEventEnvelope.builder()
                .eventId(Ids.newId())
                .eventType(context + ".Thing.Happened")
                .occurredAt(Instant.now())
                .actor(new Actor("system_scheduler", "tester", null, null, null))
                .context(context)
                .aggregateType("Thing")
                .aggregateId(UUID.randomUUID())
                .aggregateVersion(aggregateVersion)
                .correlationId(Ids.newId())
                .payload(Map.of("amount", 100, "note", "hello"));
    }
}
