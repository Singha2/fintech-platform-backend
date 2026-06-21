package com.arthvritt.platform.adminiam;

import com.arthvritt.platform.audit.Actor;
import com.arthvritt.platform.audit.AuditEventEnvelope;
import com.arthvritt.platform.audit.AuditLog;
import com.arthvritt.platform.shared.Ids;
import com.arthvritt.platform.shared.crypto.SecretCipher;
import com.arthvritt.platform.shared.error.ValidationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * BC10 TOTP enrollment (C7 "TOTP preferred", DL-035). Self-service and <b>pre-MFA</b>: an invited
 * admin has no assertion yet (they are enrolling the very factor that mints one), so these are plain
 * audited operations, <i>not</i> {@code CommandGateway} commands. The secret is app-layer encrypted
 * ({@link SecretCipher}); {@code last_used_at} is stamped on first successful verify and is the
 * "confirmed" signal the AU10.1 activation gate (M4b {@code AdminUserService}) checks.
 */
@Service
public class TotpService {

    private static final String ISSUER = "Arthvritt Platform";
    private static final int VERIFY_WINDOW = 1; // ±1 step (±30s) for clock skew
    private static final String AUDIT_CONTEXT = "admin_iam";

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final AuditLog auditLog;

    public TotpService(JdbcTemplate jdbc, SecretCipher cipher, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.auditLog = auditLog;
    }

    /** Enrolls a (not-yet-confirmed) TOTP factor; returns the otpauth URI to render as a QR code. */
    @Transactional
    public TotpEnrollment enrollTotp(UUID adminUserId, String label) {
        AdminContact admin = adminContact(adminUserId);
        byte[] secret = Totp.newSecret();
        UUID factorId = Ids.newId();
        jdbc.update("INSERT INTO auth_mfa_factor (factor_id, identity_id, kind, secret_encrypted, label) "
                        + "VALUES (?, ?, 'totp'::mfa_factor_kind_enum, ?, ?)",
                factorId, admin.identityId(), cipher.encrypt(secret), label);
        audit("admin_iam.Mfa.Enrolled", adminUserId, admin.identityId(),
                Map.of("factor_type", "totp", "factor_id", factorId.toString()));
        return new TotpEnrollment(factorId, Totp.otpauthUri(ISSUER, admin.email(), secret));
    }

    /** Confirms possession by verifying a code; stamps {@code last_used_at} so AU10.1 can count it. */
    @Transactional
    public void confirmTotp(UUID factorId, String code) {
        Factor factor = jdbc.query(
                "SELECT identity_id, kind::text AS kind, secret_encrypted, (revoked_at IS NULL) AS active, "
                        + "(last_used_at IS NOT NULL) AS confirmed "
                        + "FROM auth_mfa_factor WHERE factor_id = ?",
                rs -> rs.next()
                        ? new Factor(rs.getObject("identity_id", UUID.class), rs.getString("kind"),
                        rs.getBytes("secret_encrypted"), rs.getBoolean("active"), rs.getBoolean("confirmed"))
                        : null,
                factorId);
        if (factor == null || !"totp".equals(factor.kind()) || !factor.active()) {
            throw new ValidationException("no active TOTP factor: " + factorId);
        }
        // One-time enrollment confirmation: reject a replayed code against an already-confirmed factor.
        // (Login-time TOTP, which needs full last-step replay tracking, is a deferred follow-up.)
        if (factor.confirmed()) {
            throw new ValidationException("TOTP factor already confirmed: " + factorId);
        }
        if (!Totp.verify(cipher.decrypt(factor.secretEncrypted()), code, Instant.now(), VERIFY_WINDOW)) {
            throw new ValidationException("invalid_totp_code");
        }
        jdbc.update("UPDATE auth_mfa_factor SET last_used_at = now() WHERE factor_id = ? AND last_used_at IS NULL",
                factorId);
    }

    private AdminContact adminContact(UUID adminUserId) {
        AdminContact c = jdbc.query(
                "SELECT identity_id, email FROM admin_user WHERE admin_user_id = ?",
                rs -> rs.next() ? new AdminContact(rs.getObject("identity_id", UUID.class), rs.getString("email")) : null,
                adminUserId);
        if (c == null) {
            throw new ValidationException("admin user not found: " + adminUserId);
        }
        return c;
    }

    private void audit(String eventType, UUID adminUserId, UUID identityId, Map<String, Object> payload) {
        auditLog.append(AuditEventEnvelope.builder()
                .eventId(Ids.newId())
                .eventType(eventType)
                .occurredAt(Instant.now())
                .actor(new Actor("admin_user", identityId.toString(), null, null, null))
                .context(AUDIT_CONTEXT)
                .aggregateType("AdminUser")
                .aggregateId(adminUserId)
                .aggregateVersion(1)
                .correlationId(Ids.newId())
                .payload(payload)
                .build());
    }

    public record TotpEnrollment(UUID factorId, String otpauthUri) {
    }

    private record AdminContact(UUID identityId, String email) {
    }

    private record Factor(UUID identityId, String kind, byte[] secretEncrypted, boolean active,
                          boolean confirmed) {
    }
}
