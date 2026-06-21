package com.arthvritt.platform.auth;

import com.arthvritt.platform.audit.Actor;
import com.arthvritt.platform.audit.AuditEventEnvelope;
import com.arthvritt.platform.audit.AuditLog;
import com.arthvritt.platform.notification.NotificationPort;
import com.arthvritt.platform.notification.NotificationRequest;
import com.arthvritt.platform.shared.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * M3a authentication: identity provisioning, password auth (argon2id), and SMS-OTP MFA that mints the
 * {@code mfa_assertion_id} (non-negotiable #2). Native SQL onto the V3 auth tables; every auth event
 * is audited via {@link AuditLog} (M2). OTP delivery goes through the BC15 {@link NotificationPort}
 * (stubbed). Sessions / freshness / tenant claims are M3b.
 */
@Service
public class AuthService {

    static final int OTP_TTL_SECONDS = 300;     // 5 minutes
    static final int OTP_MAX_ATTEMPTS = 5;
    private static final int OTP_BOUND = 1_000_000; // 6 digits
    private static final String AUDIT_CONTEXT = "auth";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final NotificationPort notifications;
    private final AuditLog auditLog;
    private final SecureRandom random = new SecureRandom();

    public AuthService(JdbcTemplate jdbc, PasswordEncoder encoder,
                       NotificationPort notifications, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.notifications = notifications;
        this.auditLog = auditLog;
    }

    /**
     * Creates an active identity (a primitive). The enrollment-gated invited→active lifecycle (admin
     * needs MFA, auditor needs a validity window) is M4/onboarding's concern — see DL-BE-016.
     */
    @Transactional
    public UUID provisionIdentity(String kind, String email, String phoneE164, String displayName) {
        UUID id = Ids.newId();
        jdbc.update("INSERT INTO auth_identity "
                        + "(identity_id, kind, email, phone_e164, display_name, status) "
                        + "VALUES (?, ?::identity_kind_enum, ?, ?, ?, 'active'::identity_status_enum)",
                id, kind, email, phoneE164, displayName);
        audit("auth.Identity.Provisioned", "Identity", id, kind, id.toString(), Map.of("email", email));
        return id;
    }

    /** Sets (or rotates) the password. Revokes any prior active password credential first, since the */
    /* schema permits only one active password per identity. */
    @Transactional
    public void setPassword(UUID identityId, String rawPassword) {
        jdbc.update("UPDATE auth_credential SET revoked_at = now() "
                + "WHERE identity_id = ? AND kind = 'password' AND revoked_at IS NULL", identityId);
        jdbc.update("INSERT INTO auth_credential (credential_id, identity_id, kind, secret_hash) "
                        + "VALUES (?, ?, 'password'::credential_kind_enum, ?)",
                Ids.newId(), identityId, encoder.encode(rawPassword));
    }

    @Transactional
    public PasswordResult authenticatePassword(String email, String rawPassword) {
        Identity identity = jdbc.query(
                "SELECT identity_id, kind::text AS kind, status::text AS status, valid_from, valid_until "
                        + "FROM auth_identity WHERE email = ?",
                rs -> rs.next()
                        ? new Identity(rs.getObject("identity_id", UUID.class), rs.getString("kind"),
                        rs.getString("status"), instantOf(rs.getObject("valid_from", OffsetDateTime.class)),
                        instantOf(rs.getObject("valid_until", OffsetDateTime.class)))
                        : null,
                email);
        if (identity == null) {
            audit("auth.Identity.PasswordRejected", "Identity", anonymousId(email), "anonymous", email,
                    Map.of("reason", "unknown_email"));
            return new PasswordResult(false, null);
        }
        String gateFailure = loginGate(identity);
        if (gateFailure != null) {
            audit("auth.Identity.PasswordRejected", "Identity", identity.id(), identity.kind(),
                    identity.id().toString(), Map.of("reason", gateFailure));
            return new PasswordResult(false, null);
        }
        String secretHash = jdbc.query(
                "SELECT secret_hash FROM auth_credential WHERE identity_id = ? AND kind = 'password' "
                        + "AND revoked_at IS NULL ORDER BY created_at DESC LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null, identity.id());
        if (secretHash == null || !encoder.matches(rawPassword, secretHash)) {
            audit("auth.Identity.PasswordRejected", "Identity", identity.id(), identity.kind(),
                    identity.id().toString(), Map.of("reason", "bad_password"));
            return new PasswordResult(false, null);
        }
        jdbc.update("UPDATE auth_credential SET last_used_at = now() "
                + "WHERE identity_id = ? AND kind = 'password'", identity.id());
        audit("auth.Identity.PasswordVerified", "Identity", identity.id(), identity.kind(),
                identity.id().toString(), Map.of());
        return new PasswordResult(true, identity.id());
    }

    /** Returns a rejection reason if the identity may not authenticate, else null. */
    private static String loginGate(Identity identity) {
        if (!"active".equals(identity.status())) {
            return "status_" + identity.status();
        }
        Instant now = Instant.now();
        if (identity.validFrom() != null && now.isBefore(identity.validFrom())) {
            return "before_validity_window";
        }
        if (identity.validUntil() != null && now.isAfter(identity.validUntil())) {
            return "after_validity_window";
        }
        return null;
    }

    @Transactional
    public UUID issueLoginOtp(UUID identityId) {
        jdbc.update("UPDATE auth_otp_challenge SET status = 'superseded' "
                + "WHERE identity_id = ? AND purpose = 'login_mfa' AND status = 'active'", identityId);

        String code = String.format("%06d", random.nextInt(OTP_BOUND));
        UUID challengeId = Ids.newId();
        jdbc.update("INSERT INTO auth_otp_challenge "
                        + "(challenge_id, identity_id, purpose, code_hash, delivery_channel, expires_at, max_attempts) "
                        + "VALUES (?, ?, 'login_mfa'::otp_purpose_enum, ?, 'sms', "
                        + "now() + (interval '1 second' * ?), ?)",
                challengeId, identityId, encoder.encode(code), OTP_TTL_SECONDS, OTP_MAX_ATTEMPTS);

        IdentityContact contact = jdbc.queryForObject(
                "SELECT phone_e164, kind::text AS kind FROM auth_identity WHERE identity_id = ?",
                (rs, n) -> new IdentityContact(rs.getString("phone_e164"), rs.getString("kind")), identityId);

        // Send only after the challenge row + audit envelope commit, so a rolled-back transaction
        // never leaves a real SMS dispatched for a non-existent challenge.
        sendAfterCommit(new NotificationRequest(identityId, "sms", "login_otp",
                Map.of("code", code, "phone", contact == null || contact.phone() == null ? "" : contact.phone())));

        audit("auth.Otp.Issued", "OtpChallenge", challengeId,
                contact == null ? "unknown" : contact.kind(), identityId.toString(),
                Map.of("purpose", "login_mfa"));
        return challengeId;
    }

    /**
     * Verifies an OTP. The challenge row is locked {@code FOR UPDATE} so concurrent verifies serialize
     * — preventing a double-consume (two assertions) or an attempt-cap bypass. A failed attempt is
     * normal flow: it returns {@code OtpResult.failed(...)} and commits its side effects (attempts++,
     * audit), rather than throwing (which would roll the counter back).
     */
    @Transactional
    public OtpResult verifyOtp(UUID challengeId, String code) {
        OtpChallenge c = jdbc.query(
                "SELECT c.identity_id, c.code_hash, c.attempts, c.max_attempts, c.status::text AS status, "
                        + "(c.expires_at <= now()) AS expired, i.kind::text AS kind "
                        + "FROM auth_otp_challenge c JOIN auth_identity i ON i.identity_id = c.identity_id "
                        + "WHERE c.challenge_id = ? FOR UPDATE OF c",
                rs -> rs.next()
                        ? new OtpChallenge(rs.getObject("identity_id", UUID.class), rs.getString("code_hash"),
                        rs.getInt("attempts"), rs.getInt("max_attempts"), rs.getString("status"),
                        rs.getBoolean("expired"), rs.getString("kind"))
                        : null,
                challengeId);
        if (c == null) {
            return OtpResult.failed("not_found");
        }
        if (!"active".equals(c.status())) {
            otpFailed(challengeId, c, "not_active");
            return OtpResult.failed("not_active");
        }
        if (c.expired()) {
            jdbc.update("UPDATE auth_otp_challenge SET status = 'expired' "
                    + "WHERE challenge_id = ? AND status = 'active'", challengeId);
            otpFailed(challengeId, c, "expired");
            return OtpResult.failed("expired");
        }
        if (c.attempts() >= c.maxAttempts()) {
            otpFailed(challengeId, c, "attempts_exceeded");
            return OtpResult.failed("attempts_exceeded");
        }
        if (!encoder.matches(code, c.codeHash())) {
            jdbc.update("UPDATE auth_otp_challenge SET attempts = attempts + 1 WHERE challenge_id = ?",
                    challengeId);
            otpFailed(challengeId, c, "bad_code");
            return OtpResult.failed("bad_code");
        }

        UUID assertionId = Ids.newId();
        int consumed = jdbc.update("UPDATE auth_otp_challenge SET status = 'consumed', consumed_at = now(), "
                + "assertion_id = ? WHERE challenge_id = ? AND status = 'active'", assertionId, challengeId);
        if (consumed == 0) { // defensive: row already consumed by a racing verify (FOR UPDATE makes this unreachable)
            otpFailed(challengeId, c, "not_active");
            return OtpResult.failed("not_active");
        }
        audit("auth.Otp.Consumed", "OtpChallenge", challengeId, c.kind(), c.identityId().toString(), Map.of());
        return OtpResult.verified(new MfaAssertion(assertionId, c.identityId(), Instant.now()));
    }

    private void otpFailed(UUID challengeId, OtpChallenge c, String reason) {
        audit("auth.Otp.Failed", "OtpChallenge", challengeId, c.kind(), c.identityId().toString(),
                Map.of("reason", reason));
    }

    private void sendAfterCommit(NotificationRequest request) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notifications.send(request);
                }
            });
        } else {
            notifications.send(request);
        }
    }

    private static Instant instantOf(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }

    private static UUID anonymousId(String email) {
        return UUID.nameUUIDFromBytes(("email:" + email).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void audit(String eventType, String aggregateType, UUID aggregateId,
                       String actorType, String actorId, Map<String, Object> payload) {
        auditLog.append(AuditEventEnvelope.builder()
                .eventId(Ids.newId())
                .eventType(eventType)
                .occurredAt(Instant.now())
                .actor(new Actor(actorType, actorId, null, null, null))
                .context(AUDIT_CONTEXT)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .aggregateVersion(1)
                .correlationId(Ids.newId())
                .payload(payload)
                .build());
    }

    private record Identity(UUID id, String kind, String status, Instant validFrom, Instant validUntil) {
    }

    private record IdentityContact(String phone, String kind) {
    }

    private record OtpChallenge(UUID identityId, String codeHash, int attempts, int maxAttempts,
                                String status, boolean expired, String kind) {
    }
}
