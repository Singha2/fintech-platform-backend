package com.arthvritt.platform.adminiam;

import com.arthvritt.platform.auth.AuthService;
import com.arthvritt.platform.shared.Ids;
import com.arthvritt.platform.shared.error.ValidationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Break-glass admin bootstrap — provisions a fully usable <b>super_admin</b> (its {@code auth_identity}, an
 * <b>active</b> {@code admin_user}, a super_admin role assignment, and a login password) in one shot, so the
 * very first admin can exist before any other does. This deliberately bypasses the {@code CommandGateway}
 * (maker-checker / MFA / super_admin authz / the invited→active TOTP gate) — it is the <i>seed</i> of the
 * admin trust chain, so there is no prior admin to authorise it. The only gate is the static API key enforced
 * by {@link BootstrapAdminController}; production supplies that key from a secret manager.
 *
 * <p>Deliberately restricted to <b>super_admin only</b>: bootstrap seeds the trust chain, then every other
 * admin (any role) is created through the normal maker-checker {@code /admin-users} flow. Mirrors what
 * {@code DevDataSeeder} does per admin, but on demand over HTTP rather than on startup.
 */
@Service
public class BootstrapAdminService {

    private final JdbcTemplate jdbc;
    private final AuthService auth;

    public BootstrapAdminService(JdbcTemplate jdbc, AuthService auth) {
        this.jdbc = jdbc;
        this.auth = auth;
    }

    /**
     * Provisions an active <b>super_admin</b> with the given password. Fails with a clean 400 if the email is
     * already taken. Runs in one transaction so a half-provisioned admin never persists.
     *
     * @return the new {@code admin_user_id}
     */
    @Transactional
    public UUID provisionSuperAdmin(String email, String displayName, String phoneE164, String password) {
        if (emailTaken(email)) {
            throw new ValidationException("admin email already in use: " + email);
        }
        try {
            UUID identityId = auth.provisionIdentity("admin_user", email, phoneE164, displayName);
            UUID adminUserId = Ids.newId();
            jdbc.update("INSERT INTO admin_user (admin_user_id, identity_id, email, display_name, status) "
                    + "VALUES (?, ?, ?, ?, 'active')", adminUserId, identityId, email, displayName);
            // Self-assigned: there is no prior admin to be the assigner (the FK is satisfied by the row above,
            // inserted in this same transaction). This is unique to bootstrap; normal role grants name a grantor.
            jdbc.update("INSERT INTO admin_role_assignment (admin_user_id, role, status, assigned_by) "
                    + "VALUES (?, ?::admin_role, 'active', ?)", adminUserId, AdminRole.SUPER_ADMIN.wire(), adminUserId);
            auth.setPassword(identityId, password);
            return adminUserId;
        } catch (DuplicateKeyException e) {
            // Lost a race on the email/identity UNIQUE, or a reused id — surface as a clean 400, not a 500.
            throw new ValidationException("admin email or identity already in use: " + email);
        }
    }

    private boolean emailTaken(String email) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM admin_user WHERE email = ?", Integer.class, email);
        return n != null && n > 0;
    }
}
