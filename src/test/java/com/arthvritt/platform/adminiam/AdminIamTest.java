package com.arthvritt.platform.adminiam;

import com.arthvritt.platform.AbstractIntegrationTest;
import com.arthvritt.platform.auth.ActionSensitivity;
import com.arthvritt.platform.auth.AuthService;
import com.arthvritt.platform.auth.AuthSession;
import com.arthvritt.platform.auth.SessionService;
import com.arthvritt.platform.auth.TenantClaims;
import com.arthvritt.platform.command.CommandRejectedException;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.notification.StubNotifier;
import com.arthvritt.platform.shared.Ids;
import com.arthvritt.platform.shared.crypto.SecretCipher;
import com.arthvritt.platform.shared.error.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M4b invariant tests (see docs/modules/M4b-admin-iam-rbac-totp.md §7): TOTP enroll/confirm + the
 * AU10.1 activation gate, composable RBAC union, and role-authorization at the command boundary.
 * Integration against Testcontainers; commands run through the real M4a {@code CommandGateway}.
 */
class AdminIamTest extends AbstractIntegrationTest {

    @Autowired private AdminUserService adminUsers;
    @Autowired private RbacService rbac;
    @Autowired private RoleResolver roleResolver;
    @Autowired private TotpService totp;
    @Autowired private AdminBootstrap bootstrap;
    @Autowired private AuthService auth;
    @Autowired private SessionService sessions;
    @Autowired private StubNotifier notifier;
    @Autowired private SecretCipher cipher;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearNotifier() {
        notifier.clear();
    }

    @Test
    void totp_secret_is_stored_encrypted_and_confirm_stamps_last_used() { // INV-1, INV-2
        UUID adminUserId = invitedAdmin();
        TotpService.TotpEnrollment enrollment = totp.enrollTotp(adminUserId, "Pixel 7");

        assertThat(enrollment.otpauthUri()).startsWith("otpauth://totp/");
        byte[] stored = jdbc.queryForObject(
                "SELECT secret_encrypted FROM auth_mfa_factor WHERE factor_id = ?", byte[].class, enrollment.factorId());
        byte[] secret = cipher.decrypt(stored);
        assertThat(secret).hasSize(20);
        assertThat(stored).isNotEqualTo(secret); // ciphertext != plaintext
        assertThat(lastUsed(enrollment.factorId())).isNull();

        totp.confirmTotp(enrollment.factorId(), Totp.generate(secret, Instant.now()));
        assertThat(lastUsed(enrollment.factorId())).isNotNull();
    }

    @Test
    void confirm_rejects_a_wrong_code() { // INV-2
        UUID adminUserId = invitedAdmin();
        TotpService.TotpEnrollment enrollment = totp.enrollTotp(adminUserId, "Pixel 7");

        assertThatThrownBy(() -> totp.confirmTotp(enrollment.factorId(), "000000"))
                .isInstanceOf(ValidationException.class);
        assertThat(lastUsed(enrollment.factorId())).isNull();
    }

    @Test
    void activation_requires_a_confirmed_totp_factor() { // INV-1 (AU10.1)
        Actor admin = superAdminActor();
        UUID newId = Ids.newId();
        adminUsers.provisionAdminUser(req(admin, "admin_iam.AdminUser.Provision", newId, 0),
                email(), "New Admin", phone());

        // No confirmed TOTP yet → activation blocked.
        assertThatThrownBy(() -> adminUsers.activateAdminUser(req(admin, "admin_iam.AdminUser.Activate", newId, 1)))
                .isInstanceOf(ValidationException.class);
        assertThat(status(newId)).isEqualTo("invited");

        confirmTotpFor(newId);
        adminUsers.activateAdminUser(req(admin, "admin_iam.AdminUser.Activate", newId, 1));
        assertThat(status(newId)).isEqualTo("active");
        assertThat(version(newId)).isEqualTo(2);
    }

    @Test
    void rbac_effective_roles_are_the_union_of_active_assignments() { // INV-3
        Actor admin = superAdminActor();
        Actor target = activeAdminWithRole(AdminRole.OPS_EXECUTIVE);
        UUID targetId = target.adminUserId();
        UUID targetIdentity = target.identityId();

        rbac.assignRole(req(admin, "admin_iam.Role.Assign", targetId, 0), AdminRole.CREDIT_REVIEWER);
        assertThat(roleResolver.activeRoles(targetIdentity)).containsExactlyInAnyOrder("ops_executive", "credit_reviewer");

        rbac.revokeRole(req(admin, "admin_iam.Role.Revoke", targetId, 0), AdminRole.OPS_EXECUTIVE);
        assertThat(roleResolver.activeRoles(targetIdentity)).containsExactly("credit_reviewer");
    }

    @Test
    void a_disabled_admin_loses_authorization_even_with_active_role_rows() { // RoleResolver au.status gate
        Actor admin = activeAdminWithRole(AdminRole.SUPER_ADMIN);
        UUID target = activeAdminWithRole(AdminRole.CREDIT_REVIEWER).adminUserId();
        // Disable the actor directly (their super_admin role row stays 'active').
        jdbc.update("UPDATE admin_user SET status = 'disabled' WHERE admin_user_id = ?", admin.adminUserId());

        assertThat(roleResolver.activeRoles(admin.identityId())).isEmpty();
        assertThatThrownBy(() -> adminUsers.disableAdminUser(req(admin, "admin_iam.AdminUser.Disable", target, 1)))
                .isInstanceOf(CommandRejectedException.class)
                .satisfies(e -> assertThat(((CommandRejectedException) e).getErrorCode()).isEqualTo("role_not_held"));
    }

    @Test
    void totp_confirmation_is_one_time() { // replay guard
        UUID adminUserId = invitedAdmin();
        TotpService.TotpEnrollment enrollment = totp.enrollTotp(adminUserId, "Pixel 7");
        byte[] secret = cipher.decrypt(jdbc.queryForObject(
                "SELECT secret_encrypted FROM auth_mfa_factor WHERE factor_id = ?", byte[].class, enrollment.factorId()));
        totp.confirmTotp(enrollment.factorId(), Totp.generate(secret, Instant.now()));

        assertThatThrownBy(() -> totp.confirmTotp(enrollment.factorId(), Totp.generate(secret, Instant.now())))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void a_non_super_admin_cannot_disable_an_admin() { // INV-4 (authz at the boundary)
        Actor ops = activeAdminWithRole(AdminRole.OPS_EXECUTIVE);
        UUID target = activeAdminWithRole(AdminRole.SUPER_ADMIN).adminUserId();

        assertThatThrownBy(() -> adminUsers.disableAdminUser(req(ops, "admin_iam.AdminUser.Disable", target, 1)))
                .isInstanceOf(CommandRejectedException.class)
                .satisfies(e -> assertThat(((CommandRejectedException) e).getErrorCode()).isEqualTo("role_not_held"));
        assertThat(status(target)).isEqualTo("active");
    }

    @Test
    void a_super_admin_can_disable_an_admin() { // INV-4 happy path (bootstrap end-to-end)
        Actor admin = superAdminActor();
        UUID target = activeAdminWithRole(AdminRole.CREDIT_REVIEWER).adminUserId();

        adminUsers.disableAdminUser(req(admin, "admin_iam.AdminUser.Disable", target, 1));
        assertThat(status(target)).isEqualTo("disabled");
    }

    @Test
    void disabling_an_admin_cascades_to_identity_and_sessions() { // disable-cascade hardening
        Actor disabler = superAdminActor();
        // Target A: active super_admin with a password and a live session.
        String email = email();
        UUID identityId = auth.provisionIdentity("admin_user", email, phone(), "Target A");
        auth.setPassword(identityId, "Sup3r-Secret!");
        UUID targetId = Ids.newId();
        jdbc.update("INSERT INTO admin_user (admin_user_id, identity_id, email, display_name, status) "
                + "VALUES (?, ?, ?, ?, 'active')", targetId, identityId, "au-" + targetId + "@arthvritt.test", "Target A");
        jdbc.update("INSERT INTO auth_mfa_factor (factor_id, identity_id, kind, secret_encrypted, label, last_used_at) "
                + "VALUES (?, ?, 'totp'::mfa_factor_kind_enum, ?, 'seed', now())",
                Ids.newId(), identityId, cipher.encrypt(Totp.newSecret()));
        jdbc.update("INSERT INTO admin_role_assignment (admin_user_id, role, status, assigned_by) "
                + "VALUES (?, 'super_admin'::admin_role, 'active', ?)", targetId, targetId);
        AuthSession aSession = sessionFor(identityId);

        // Pre-conditions: A can authenticate, resolve its session, and is authorized.
        assertThat(auth.authenticatePassword(email, "Sup3r-Secret!").authenticated()).isTrue();
        assertThat(sessions.resolveSession(aSession.sessionId()).active()).isTrue();
        assertThat(roleResolver.activeRoles(identityId)).containsExactly("super_admin");

        adminUsers.disableAdminUser(req(disabler, "admin_iam.AdminUser.Disable", targetId, 1));

        assertThat(status(targetId)).isEqualTo("disabled");
        assertThat(auth.authenticatePassword(email, "Sup3r-Secret!").authenticated()).isFalse(); // identity disabled
        assertThat(sessions.resolveSession(aSession.sessionId()).active()).isFalse();             // session revoked
        assertThat(roleResolver.activeRoles(identityId)).isEmpty();                                // authz stripped
    }

    @Test
    void admin_email_is_globally_unique() { // INV-5
        Actor admin = superAdminActor();
        String email = email();
        adminUsers.provisionAdminUser(req(admin, "admin_iam.AdminUser.Provision", Ids.newId(), 0), email, "A", phone());

        assertThatThrownBy(() -> adminUsers.provisionAdminUser(
                req(admin, "admin_iam.AdminUser.Provision", Ids.newId(), 0), email, "B", phone()))
                .isInstanceOf(ValidationException.class);
    }

    // --- helpers -------------------------------------------------------------------------------

    private Actor superAdminActor() {
        AdminBootstrap.Seeded seeded = bootstrap.seedSuperAdmin(email(), "Root", phone());
        return new Actor(seeded.adminUserId(), seeded.identityId(), sessionFor(seeded.identityId()));
    }

    private Actor activeAdminWithRole(AdminRole role) {
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

    /** An invited admin_user (no roles, no session) — the target of enroll/activate. */
    private UUID invitedAdmin() {
        UUID identityId = auth.provisionIdentity("admin_user", email(), phone(), "Invitee");
        UUID adminUserId = Ids.newId();
        jdbc.update("INSERT INTO admin_user (admin_user_id, identity_id, email, display_name, status) "
                        + "VALUES (?, ?, ?, ?, 'invited')",
                adminUserId, identityId, "au-" + adminUserId + "@arthvritt.test", "Invitee");
        return adminUserId;
    }

    private void confirmTotpFor(UUID adminUserId) {
        TotpService.TotpEnrollment enrollment = totp.enrollTotp(adminUserId, "factor");
        byte[] stored = jdbc.queryForObject(
                "SELECT secret_encrypted FROM auth_mfa_factor WHERE factor_id = ?", byte[].class, enrollment.factorId());
        totp.confirmTotp(enrollment.factorId(), Totp.generate(cipher.decrypt(stored), Instant.now()));
    }

    private AuthSession sessionFor(UUID identityId) {
        UUID challengeId = auth.issueLoginOtp(identityId);
        UUID assertionId = auth.verifyOtp(challengeId, notifier.lastCodeFor(identityId).orElseThrow())
                .assertion().assertionId();
        UUID sessionId = sessions.establishSession(identityId, assertionId, TenantClaims.empty(), null, null);
        return sessions.resolveSession(sessionId).session();
    }

    private CommandRequest req(Actor actor, String commandType, UUID aggregateId, int expectedVersion) {
        return new CommandRequest(actor.session(), Ids.newId(), "admin_iam", commandType, "AdminUser",
                aggregateId, expectedVersion, "admin_user", ActionSensitivity.SENSITIVE);
    }

    private String status(UUID adminUserId) {
        return jdbc.queryForObject("SELECT status::text FROM admin_user WHERE admin_user_id = ?",
                String.class, adminUserId);
    }

    private int version(UUID adminUserId) {
        return jdbc.queryForObject("SELECT aggregate_version FROM admin_user WHERE admin_user_id = ?",
                Integer.class, adminUserId);
    }

    private Instant lastUsed(UUID factorId) {
        return jdbc.queryForObject("SELECT last_used_at FROM auth_mfa_factor WHERE factor_id = ?",
                Instant.class, factorId);
    }

    private static String email() {
        return "user-" + UUID.randomUUID() + "@arthvritt.test";
    }

    private static String phone() {
        return "+9198" + (10000000 + new java.util.Random().nextInt(89999999));
    }

    private record Actor(UUID adminUserId, UUID identityId, AuthSession session) {
    }
}
