package com.arthvritt.platform.adminiam;

import com.arthvritt.platform.AbstractIntegrationTest;
import com.arthvritt.platform.auth.ActionSensitivity;
import com.arthvritt.platform.auth.AuthService;
import com.arthvritt.platform.auth.AuthSession;
import com.arthvritt.platform.auth.SessionService;
import com.arthvritt.platform.auth.TenantClaims;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.notification.StubNotifier;
import com.arthvritt.platform.shared.Ids;
import com.arthvritt.platform.shared.crypto.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Shared fixtures for the admin-IAM integration tests (M4b–M4d). Holds the common beans and the
 * seed/session/request helpers that were duplicated across {@code AdminIamTest},
 * {@code SodEnforcementTest}, and {@code MakerCheckerTest} (extracted per [[DL-BE-021]]). Subclasses add
 * their own service beans and slice-specific helpers.
 */
abstract class AbstractAdminIamTest extends AbstractIntegrationTest {

    @Autowired protected AdminBootstrap bootstrap;
    @Autowired protected AuthService auth;
    @Autowired protected SessionService sessions;
    @Autowired protected StubNotifier notifier;
    @Autowired protected SecretCipher cipher;
    @Autowired protected JdbcTemplate jdbc;

    @BeforeEach
    void clearNotifier() {
        notifier.clear();
    }

    /** A seeded, active {@code super_admin} with a live MFA-fresh session. */
    protected Actor superAdminActor() {
        AdminBootstrap.Seeded seeded = bootstrap.seedSuperAdmin(email(), "Root", phone());
        return new Actor(seeded.adminUserId(), seeded.identityId(), sessionFor(seeded.identityId()));
    }

    /** An active admin holding {@code role} (confirmed TOTP + a live session). */
    protected Actor activeAdminWithRole(AdminRole role) {
        UUID identityId = auth.provisionIdentity("admin_user", email(), phone(), "Admin");
        UUID adminUserId = Ids.newId();
        jdbc.update("INSERT INTO admin_user (admin_user_id, identity_id, email, display_name, status) "
                        + "VALUES (?, ?, ?, ?, 'active')",
                adminUserId, identityId, "au-" + adminUserId + "@arthvritt.test", "Admin");
        jdbc.update("INSERT INTO auth_mfa_factor (factor_id, identity_id, kind, secret_encrypted, label, last_used_at) "
                        + "VALUES (?, ?, 'totp'::mfa_factor_kind_enum, ?, 'seed', now())",
                Ids.newId(), identityId, cipher.encrypt(Totp.newSecret()));
        jdbc.update("INSERT INTO admin_role_assignment (admin_user_id, role, status, assigned_by) "
                        + "VALUES (?, ?::admin_role, 'active', ?)", adminUserId, role.wire(), adminUserId);
        return new Actor(adminUserId, identityId, sessionFor(identityId));
    }

    /** An invited admin_user (no roles, no session) — a target for enroll/activate/role-assign. */
    protected UUID invitedAdmin() {
        return insertAdmin("invited");
    }

    /** An active admin_user (no role/session) — a target for disable/maker-checker flows. */
    protected UUID activeAdmin() {
        return insertAdmin("active");
    }

    private UUID insertAdmin(String status) {
        UUID identityId = auth.provisionIdentity("admin_user", email(), phone(), "Target");
        UUID adminUserId = Ids.newId();
        jdbc.update("INSERT INTO admin_user (admin_user_id, identity_id, email, display_name, status) "
                        + "VALUES (?, ?, ?, ?, ?::admin_user_status)",
                adminUserId, identityId, "au-" + adminUserId + "@arthvritt.test", "Target", status);
        return adminUserId;
    }

    protected AuthSession sessionFor(UUID identityId) {
        UUID challengeId = auth.issueLoginOtp(identityId);
        UUID assertionId = auth.verifyOtp(challengeId, notifier.lastCodeFor(identityId).orElseThrow())
                .assertion().assertionId();
        UUID sessionId = sessions.establishSession(identityId, assertionId, TenantClaims.empty(), null, null);
        return sessions.resolveSession(sessionId).session();
    }

    protected CommandRequest req(Actor actor, String commandType, UUID aggregateId, int expectedVersion) {
        return new CommandRequest(actor.session(), Ids.newId(), "admin_iam", commandType, "AdminUser",
                aggregateId, expectedVersion, "admin_user", ActionSensitivity.SENSITIVE);
    }

    protected CommandRequest req(Actor actor, String commandType, UUID aggregateId) {
        return req(actor, commandType, aggregateId, 0);
    }

    protected String status(UUID adminUserId) {
        return jdbc.queryForObject("SELECT status::text FROM admin_user WHERE admin_user_id = ?",
                String.class, adminUserId);
    }

    protected int envelopes(String eventType, UUID aggregateId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, eventType, aggregateId);
    }

    protected static String email() {
        return "user-" + UUID.randomUUID() + "@arthvritt.test";
    }

    protected static String phone() {
        return "+9198" + (10000000 + new java.util.Random().nextInt(89999999));
    }

    protected record Actor(UUID adminUserId, UUID identityId, AuthSession session) {
    }
}
