package com.arthvritt.platform.auth;

import com.arthvritt.platform.audit.Actor;
import com.arthvritt.platform.audit.AuditEnvelopes;
import com.arthvritt.platform.audit.AuditLog;
import com.arthvritt.platform.shared.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * M3b sessions, MFA-freshness check & tenant claims (see {@code docs/modules/M3b-sessions-and-mfa-freshness.md}).
 * M3a <i>minted</i> the {@code mfa_assertion_id} (non-negotiable #2); this service is the consumer
 * side: it establishes the authenticated session carrying that assertion, resolves it (rolling the
 * idle window, lazily expiring), revokes it, and implements {@link #isMfaFresh} — the gate every
 * admin state-changing command (M4+) calls. Native SQL onto the V3 {@code auth_session} table; every
 * session lifecycle event is audited via {@link AuditLog} (M2).
 *
 * <p>No HTTP/cookie layer here — that filter lands with the first authenticated endpoint (Walking
 * Skeleton); this is the service substrate it will call (DL-BE-017).
 */
@Service
public class SessionService {

    /** Rolling idle timeout, refreshed on each resolve. Proposed default; BC10 policy owns it (DL-BE-017). */
    static final int IDLE_TTL_SECONDS = 1800;          // 30 minutes
    /** Hard ceiling no idle-roll can exceed; re-auth required past it. */
    static final int ABSOLUTE_TTL_SECONDS = 28_800;    // 8 hours
    private static final String AUDIT_CONTEXT = "auth";
    private static final String AGGREGATE_TYPE = "Session";

    private final JdbcTemplate jdbc;
    private final AuditLog auditLog;
    private final ObjectMapper mapper;

    public SessionService(JdbcTemplate jdbc, AuditLog auditLog, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.auditLog = auditLog;
        this.mapper = mapper;
    }

    /**
     * Establishes a session after a successful password + OTP login. The MFA assertion minted by
     * {@link AuthService#verifyOtp} is stamped onto the row; the IAM-issued {@code claims} are
     * serialised into {@code tenant_claims} (never trusted from the client, INV-5).
     */
    @Transactional
    public UUID establishSession(UUID identityId, UUID mfaAssertionId, TenantClaims claims,
                                 String clientIp, String userAgent) {
        UUID sessionId = Ids.newId();
        String kind = kindOf(identityId);
        jdbc.update("INSERT INTO auth_session "
                        + "(session_id, identity_id, idle_expires_at, absolute_expires_at, "
                        + " mfa_assertion_id, tenant_claims, client_ip, user_agent) "
                        + "VALUES (?, ?, now() + (interval '1 second' * ?), "
                        + "now() + (interval '1 second' * ?), ?, ?::jsonb, ?::inet, ?)",
                sessionId, identityId, IDLE_TTL_SECONDS, ABSOLUTE_TTL_SECONDS,
                mfaAssertionId, toJson(claims), clientIp, userAgent);

        // Both envelopes belong to one logical act, so they share a correlation_id (B2 §3.10): an
        // auditor querying by correlation_id can tie the claim issuance back to the establishment.
        UUID correlationId = Ids.newId();
        audit("auth.Session.Established", sessionId, identityId, kind, mfaAssertionId, correlationId, Map.of());
        audit("auth.TenantClaim.Issued", sessionId, identityId, kind, mfaAssertionId, correlationId,
                Map.of("claims", claims.values()));
        return sessionId;
    }

    /**
     * Resolves a session by id: validates status + both expiries, and on a live session rolls the
     * idle window forward (the only state change permitted on a read path, P4). A session past its
     * idle or absolute expiry is lazily transitioned to {@code expired} here — there is no scheduler
     * in M3b (DL-BE-017), and every resolve re-checks both expiries, so a stale row is harmless.
     */
    @Transactional
    public SessionResolution resolveSession(UUID sessionId) {
        SessionRow r = loadForUpdate(sessionId);
        if (r == null) {
            return SessionResolution.terminated("not_found");
        }
        if ("revoked".equals(r.status())) {
            return SessionResolution.terminated("revoked");
        }
        if (!"active".equals(r.status())) { // already 'expired'
            return SessionResolution.terminated("expired");
        }
        if (r.absExpired() || r.idleExpired()) {
            jdbc.update("UPDATE auth_session SET status = 'expired' "
                    + "WHERE session_id = ? AND status = 'active'", sessionId);
            String reason = r.absExpired() ? "absolute_expired" : "idle_expired";
            audit("auth.Session.Expired", sessionId, r.identityId(), r.kind(), r.mfaAssertionId(),
                    Map.of("reason", reason));
            return SessionResolution.terminated(reason);
        }

        // Roll the idle window, clamped to the absolute ceiling so auth_session_idle_within_absolute
        // can never be violated, and RETURN the two written columns in the same statement (the rest of
        // the row is already in `r`, locked in this txn). The per-request roll is intentionally NOT
        // audited (noise; last_seen_at is the durable record — DL-BE-017).
        Rolled rolled = jdbc.query("UPDATE auth_session SET last_seen_at = now(), "
                        + "idle_expires_at = LEAST(now() + (interval '1 second' * ?), absolute_expires_at) "
                        + "WHERE session_id = ? AND status = 'active' "
                        + "RETURNING last_seen_at, idle_expires_at",
                rs -> rs.next()
                        ? new Rolled(instantOf(rs.getObject(1, OffsetDateTime.class)),
                                instantOf(rs.getObject(2, OffsetDateTime.class)))
                        : null,
                IDLE_TTL_SECONDS, sessionId);
        if (rolled == null) { // defensive: the locked row was active a statement ago, so unreachable
            return SessionResolution.terminated("not_found");
        }
        return SessionResolution.active(new AuthSession(r.sessionId(), r.identityId(), "active",
                r.issuedAt(), rolled.lastSeenAt(), rolled.idleExpiresAt(), r.absoluteExpiresAt(),
                r.mfaAssertionId(), parseClaims(r.tenantClaimsJson())));
    }

    /** Explicit logout / admin kill. A no-op (no envelope) on a missing or already-terminal session. */
    @Transactional
    public void revokeSession(UUID sessionId) {
        SessionRow r = loadForUpdate(sessionId);
        if (r == null || !"active".equals(r.status())) {
            return;
        }
        jdbc.update("UPDATE auth_session SET status = 'revoked', revoked_at = now() "
                + "WHERE session_id = ? AND status = 'active'", sessionId);
        audit("auth.Session.Revoked", sessionId, r.identityId(), r.kind(), r.mfaAssertionId(), Map.of());
    }

    /**
     * The MFA-freshness gate (C7, B4 §6.4, AU10.3). True only when the session carries an assertion
     * whose underlying OTP challenge was consumed within the band's window. A NULL assertion, an
     * unconsumed challenge, or a stale {@code consumed_at} ⇒ not fresh. Pure read; the window is
     * evaluated against the server-side {@code consumed_at} only (no client timestamp, INV-3/INV-5).
     */
    public boolean isMfaFresh(AuthSession session, ActionSensitivity sensitivity) {
        if (session == null || session.mfaAssertionId() == null) {
            return false;
        }
        // purpose = 'login_mfa' is belt-and-braces: assertion_id is only ever stamped on a consumed
        // login_mfa challenge (schema COMMENT), but that is a convention, not a DB CHECK — pin it here
        // so the freshness gate can never be satisfied by a non-login assertion.
        Boolean fresh = jdbc.query(
                "SELECT (consumed_at > now() - (interval '1 second' * ?)) "
                        + "FROM auth_otp_challenge "
                        + "WHERE assertion_id = ? AND status = 'consumed' AND purpose = 'login_mfa'",
                rs -> rs.next() ? rs.getBoolean(1) : null,
                sensitivity.windowSeconds(), session.mfaAssertionId());
        return Boolean.TRUE.equals(fresh);
    }

    // --- internals -----------------------------------------------------------------------------

    private static final String SELECT_COLS = "s.session_id, s.identity_id, i.kind::text AS kind, "
            + "s.status::text AS status, s.issued_at, s.last_seen_at, s.idle_expires_at, "
            + "s.absolute_expires_at, s.revoked_at, s.mfa_assertion_id, s.tenant_claims::text AS tenant_claims, "
            + "(s.idle_expires_at <= now()) AS idle_expired, (s.absolute_expires_at <= now()) AS abs_expired";

    private SessionRow loadForUpdate(UUID sessionId) {
        return jdbc.query("SELECT " + SELECT_COLS + " FROM auth_session s "
                        + "JOIN auth_identity i ON i.identity_id = s.identity_id "
                        + "WHERE s.session_id = ? FOR UPDATE OF s",
                rs -> rs.next() ? mapRow(rs) : null, sessionId);
    }

    private SessionRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SessionRow(
                rs.getObject("session_id", UUID.class),
                rs.getObject("identity_id", UUID.class),
                rs.getString("kind"),
                rs.getString("status"),
                instantOf(rs.getObject("issued_at", OffsetDateTime.class)),
                instantOf(rs.getObject("last_seen_at", OffsetDateTime.class)),
                instantOf(rs.getObject("idle_expires_at", OffsetDateTime.class)),
                instantOf(rs.getObject("absolute_expires_at", OffsetDateTime.class)),
                rs.getObject("mfa_assertion_id", UUID.class),
                rs.getString("tenant_claims"),
                rs.getBoolean("idle_expired"),
                rs.getBoolean("abs_expired"));
    }

    private String kindOf(UUID identityId) {
        return jdbc.queryForObject(
                "SELECT kind::text FROM auth_identity WHERE identity_id = ?", String.class, identityId);
    }

    private String toJson(TenantClaims claims) {
        try {
            return mapper.writeValueAsString(claims.values());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialise tenant_claims", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private TenantClaims parseClaims(String json) {
        if (json == null || json.isBlank()) {
            return TenantClaims.empty();
        }
        try {
            return TenantClaims.of(mapper.readValue(json, Map.class));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse tenant_claims", ex);
        }
    }

    private static Instant instantOf(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }

    /** Single-event audit: mints its own correlation_id (the event is its own logical root). */
    private void audit(String eventType, UUID sessionId, UUID identityId, String actorKind,
                       UUID mfaAssertionId, Map<String, Object> payload) {
        audit(eventType, sessionId, identityId, actorKind, mfaAssertionId, Ids.newId(), payload);
    }

    private void audit(String eventType, UUID sessionId, UUID identityId, String actorKind,
                       UUID mfaAssertionId, UUID correlationId, Map<String, Object> payload) {
        auditLog.append(AuditEnvelopes.seed(AUDIT_CONTEXT, AGGREGATE_TYPE, sessionId)
                .eventType(eventType)
                .actor(new Actor(actorKind, identityId.toString(), sessionId.toString(), mfaAssertionId, null))
                .correlationId(correlationId)
                .payload(payload)
                .build());
    }

    private record SessionRow(UUID sessionId, UUID identityId, String kind, String status,
                              Instant issuedAt, Instant lastSeenAt, Instant idleExpiresAt,
                              Instant absoluteExpiresAt, UUID mfaAssertionId, String tenantClaimsJson,
                              boolean idleExpired, boolean absExpired) {
    }

    /** The two columns the idle-roll UPDATE writes and returns, to avoid a re-read. */
    private record Rolled(Instant lastSeenAt, Instant idleExpiresAt) {
    }
}
