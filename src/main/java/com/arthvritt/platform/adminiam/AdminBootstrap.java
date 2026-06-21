package com.arthvritt.platform.adminiam;

import com.arthvritt.platform.shared.Ids;
import com.arthvritt.platform.shared.crypto.SecretCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Seeds the very first {@code super_admin} (DL-BE-019). This is the one path that bypasses the M4b
 * command boundary — there is no Super Admin yet to authorise provisioning the first one, so it can't
 * be a {@code super_admin}-gated command. It writes an active admin with a pre-confirmed TOTP factor
 * (so AU10.1 holds) and the {@code super_admin} role directly. <b>Bootstrap only</b>: every subsequent
 * admin is created through {@code AdminUserService.provisionAdminUser} under maker-checker (M4c).
 */
@Service
public class AdminBootstrap {

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    public AdminBootstrap(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Transactional
    public Seeded seedSuperAdmin(String email, String displayName, String phoneE164) {
        UUID identityId = Ids.newId();
        UUID adminUserId = Ids.newId();
        jdbc.update("INSERT INTO auth_identity (identity_id, kind, email, phone_e164, display_name, status) "
                        + "VALUES (?, 'admin_user'::identity_kind_enum, ?, ?, ?, 'active'::identity_status_enum)",
                identityId, email, phoneE164, displayName);
        jdbc.update("INSERT INTO admin_user (admin_user_id, identity_id, email, display_name, status) "
                        + "VALUES (?, ?, ?, ?, 'active')",
                adminUserId, identityId, email, displayName);
        // Pre-confirmed TOTP factor so the active status satisfies AU10.1.
        jdbc.update("INSERT INTO auth_mfa_factor (factor_id, identity_id, kind, secret_encrypted, label, last_used_at) "
                        + "VALUES (?, ?, 'totp'::mfa_factor_kind_enum, ?, 'bootstrap', now())",
                Ids.newId(), identityId, cipher.encrypt(Totp.newSecret()));
        jdbc.update("INSERT INTO admin_role_assignment (admin_user_id, role, status, assigned_by) "
                        + "VALUES (?, 'super_admin'::admin_role, 'active', ?)",
                adminUserId, adminUserId);
        return new Seeded(adminUserId, identityId);
    }

    public record Seeded(UUID adminUserId, UUID identityId) {
    }
}
