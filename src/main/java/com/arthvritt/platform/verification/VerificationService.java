package com.arthvritt.platform.verification;

import com.arthvritt.platform.audit.Actor;
import com.arthvritt.platform.audit.AuditEnvelopes;
import com.arthvritt.platform.audit.AuditLog;
import com.arthvritt.platform.shared.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * BC17 Verification ACL — the <b>fixed</b> half of the anti-corruption layer (cache, {@code
 * gate_verification} persistence, ACL idempotency, audit). It calls the swappable
 * {@link VerificationVendorClient} for the raw vendor result. A non-stale completed result for
 * {@code (subject, api)} is reused instead of issuing a new vendor call (V.1/V.4). Verification is
 * system-triggered, not a maker-checker command, so idempotency is the ACL key (the
 * {@code verification_id}) + the cache, not the M4a {@code command_id} store.
 */
@Service
public class VerificationService implements VerificationPort {

    private static final String CONTEXT = "verification";

    private final JdbcTemplate jdbc;
    private final VerificationVendorClient vendorClient;
    private final AuditLog auditLog;
    private final ObjectMapper mapper;

    public VerificationService(JdbcTemplate jdbc, VerificationVendorClient vendorClient,
                               AuditLog auditLog, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.vendorClient = vendorClient;
        this.auditLog = auditLog;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public VerificationResult verify(VerificationRequest request) {
        // Cache (V.1/V.4): a non-stale completed result for a TTL'd API is reused — no new vendor call.
        VerificationResult cached = findActive(request.subjectId(), request.api());
        if (cached != null) {
            return cached;
        }

        UUID verificationId = Ids.newId(); // = client_request_id (the vendor idempotency key)
        jdbc.update("INSERT INTO gate_verification (verification_id, subject_id, api_name, status) "
                        + "VALUES (?, ?, ?::verification_api_enum, 'requested')",
                verificationId, request.subjectId(), request.api().wire());
        audit("verification.Verification.Requested", verificationId, request, Map.of(
                "subject_id", request.subjectId().toString(), "api", request.api().wire()));

        VerificationVendorClient.VendorResponse response =
                vendorClient.call(request.api(), request.subjectId(), request.inputs());

        Instant ttlUntil = request.api().isOneShot() ? null : Instant.now().plus(request.api().ttl());
        jdbc.update("UPDATE gate_verification SET status = 'completed', extracted_fields = ?::jsonb, "
                        + "ttl_until = ?, vendor_payload_hash = ?, hmac_verified_at = now(), updated_at = now() "
                        + "WHERE verification_id = ?",
                toJson(response.extractedFields()), odt(ttlUntil), sha256(response.rawPayload()), verificationId);
        audit("verification.Verification.Completed", verificationId, request, Map.of(
                "subject_id", request.subjectId().toString(), "api", request.api().wire()));

        return new VerificationResult(verificationId, VerificationStatus.COMPLETED,
                response.extractedFields(), ttlUntil);
    }

    /** Marks a completed verification stale once its TTL has elapsed (the sweep scheduler is ops). */
    @Transactional
    public void markStale(UUID verificationId) {
        jdbc.update("UPDATE gate_verification SET status = 'stale', updated_at = now() "
                + "WHERE verification_id = ? AND status = 'completed'", verificationId);
    }

    /**
     * The cache lookup: the most-recent completed, non-stale, TTL'd result for {@code (subject, api)}.
     * One-shot APIs (null TTL) are excluded, so they are always re-issued.
     */
    private VerificationResult findActive(UUID subjectId, VerificationApi api) {
        return jdbc.query(
                "SELECT verification_id, extracted_fields::text AS fields, ttl_until "
                        + "FROM gate_verification WHERE subject_id = ? AND api_name = ?::verification_api_enum "
                        + "AND status = 'completed' AND ttl_until IS NOT NULL AND ttl_until > now() "
                        + "ORDER BY requested_at DESC LIMIT 1",
                rs -> rs.next()
                        ? new VerificationResult(rs.getObject("verification_id", UUID.class),
                        VerificationStatus.COMPLETED, parseFields(rs.getString("fields")),
                        instantOf(rs.getObject("ttl_until", OffsetDateTime.class)))
                        : null,
                subjectId, api.wire());
    }

    private void audit(String eventType, UUID verificationId, VerificationRequest request,
                       Map<String, Object> payload) {
        auditLog.append(AuditEnvelopes.seed(CONTEXT, "Verification", verificationId)
                .eventType(eventType)
                // System-triggered (the stub completes in-process). The real adapter's webhook completion
                // would carry actor_type = vendor_aggregator.
                .actor(new Actor("system", "verification_acl", null, null, null))
                .payload(payload)
                .build());
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String toJson(Map<String, Object> fields) {
        try {
            return mapper.writeValueAsString(fields);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialise extracted_fields", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseFields(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse extracted_fields", e);
        }
    }

    private static OffsetDateTime odt(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instantOf(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }
}
