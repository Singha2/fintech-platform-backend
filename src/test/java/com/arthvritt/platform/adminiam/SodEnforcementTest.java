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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M4c invariant tests (see docs/modules/M4c-sod-enforcement.md §7): the two-tier SoD engine on
 * assignRole — strict block, soft-warn-with-deviation, rules-as-data policy supersession, and the
 * once-only quarterly review. Commands run through the real M4a gateway against Testcontainers.
 */
class SodEnforcementTest extends AbstractIntegrationTest {

    @Autowired private RbacService rbac;
    @Autowired private SodPolicyService sod;
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
    void a_strict_pair_is_system_blocked_with_no_envelope() { // INV-1
        Actor admin = adminWithPolicy();
        UUID target = target();
        rbac.assignRole(req(admin, "admin_iam.Role.Assign", target, 0), AdminRole.CREDIT_REVIEWER);

        assertThatThrownBy(() -> rbac.assignRole(
                req(admin, "admin_iam.Role.Assign", target, 0), AdminRole.TREASURY_AND_SETTLEMENT))
                .isInstanceOf(CommandRejectedException.class)
                .satisfies(e -> assertThat(((CommandRejectedException) e).getErrorCode()).isEqualTo("sod_role_block"));

        assertThat(assignedRoles(target)).containsExactly("credit_reviewer"); // treasury never added
        // The blocked attempt emits no Role.Assigned envelope (only the first, clean assign did).
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE event_type = 'admin_iam.Role.Assigned' AND aggregate_id = ?",
                Integer.class, target)).isEqualTo(1);
    }

    @Test
    void a_soft_pair_requires_an_override_and_logs_exactly_one_deviation() { // INV-2
        Actor admin = adminWithPolicy();
        UUID target = target();
        rbac.assignRole(req(admin, "admin_iam.Role.Assign", target, 0), AdminRole.OPS_EXECUTIVE);

        // ops_executive + treasury_and_settlement is a soft pair → reason required.
        assertThatThrownBy(() -> rbac.assignRole(
                req(admin, "admin_iam.Role.Assign", target, 0), AdminRole.TREASURY_AND_SETTLEMENT))
                .isInstanceOf(ValidationException.class);
        assertThat(assignedRoles(target)).containsExactly("ops_executive");

        rbac.assignRole(req(admin, "admin_iam.Role.Assign", target, 0),
                AdminRole.TREASURY_AND_SETTLEMENT, "covering treasury during leave");

        assertThat(assignedRoles(target)).containsExactlyInAnyOrder("ops_executive", "treasury_and_settlement");
        UUID deviationId = jdbc.queryForObject(
                "SELECT deviation_register_entry_id FROM admin_role_assignment "
                        + "WHERE admin_user_id = ? AND role = 'treasury_and_settlement'", UUID.class, target);
        assertThat(deviationId).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_deviation_log WHERE admin_user_id = ?",
                Integer.class, target)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE event_type = 'admin_iam.SodSoftDeviation.Logged' "
                        + "AND aggregate_id = ?", Integer.class, deviationId)).isEqualTo(1);
    }

    @Test
    void publishing_a_policy_supersedes_the_prior_and_changes_behaviour() { // INV-3 + rules-as-data
        Actor admin = adminWithPolicy();
        // Publish an empty policy → the formerly-strict pair is now allowed (no code change).
        sod.publishSodPolicy(req(admin, "admin_iam.SodPolicy.Publish", Ids.newId(), 0), List.of(), List.of());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM admin_sod_policy WHERE superseded_by IS NULL", Integer.class)).isEqualTo(1);

        UUID target = target();
        rbac.assignRole(req(admin, "admin_iam.Role.Assign", target, 0), AdminRole.CREDIT_REVIEWER);
        rbac.assignRole(req(admin, "admin_iam.Role.Assign", target, 0), AdminRole.TREASURY_AND_SETTLEMENT);
        assertThat(assignedRoles(target)).containsExactlyInAnyOrder("credit_reviewer", "treasury_and_settlement");
    }

    @Test
    void a_deviation_is_reviewed_exactly_once() { // INV-4
        Actor admin = adminWithPolicy();
        UUID target = target();
        rbac.assignRole(req(admin, "admin_iam.Role.Assign", target, 0), AdminRole.OPS_EXECUTIVE);
        rbac.assignRole(req(admin, "admin_iam.Role.Assign", target, 0),
                AdminRole.TREASURY_AND_SETTLEMENT, "ops cover");
        UUID deviationId = jdbc.queryForObject(
                "SELECT deviation_register_entry_id FROM admin_deviation_log WHERE admin_user_id = ?",
                UUID.class, target);

        sod.reviewDeviation(req(admin, "admin_iam.DeviationRegister.Review", deviationId, 0), "approved");
        assertThat(jdbc.queryForObject(
                "SELECT quarterly_review_status::text FROM admin_deviation_log WHERE deviation_register_entry_id = ?",
                String.class, deviationId)).isEqualTo("reviewed");

        assertThatThrownBy(() -> sod.reviewDeviation(
                req(admin, "admin_iam.DeviationRegister.Review", deviationId, 0), "approved"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void publishing_a_malformed_pair_is_rejected() { // SoD-integrity: can't silently disable the control
        Actor admin = adminWithPolicy();

        assertThatThrownBy(() -> sod.publishSodPolicy(
                req(admin, "admin_iam.SodPolicy.Publish", Ids.newId(), 0),
                List.of(List.of("credit_reviewer", "treasury_and_settlement", "ops_executive")), List.of()))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> sod.publishSodPolicy(
                req(admin, "admin_iam.SodPolicy.Publish", Ids.newId(), 0),
                List.of(List.of("super_admin", "super_admin")), List.of()))
                .isInstanceOf(ValidationException.class);
        // The original policy is still active and still enforces the strict pair.
        UUID target = target();
        rbac.assignRole(req(admin, "admin_iam.Role.Assign", target, 0), AdminRole.CREDIT_REVIEWER);
        assertThatThrownBy(() -> rbac.assignRole(
                req(admin, "admin_iam.Role.Assign", target, 0), AdminRole.TREASURY_AND_SETTLEMENT))
                .isInstanceOf(CommandRejectedException.class);
    }

    @Test
    void re_assigning_a_held_soft_role_does_not_duplicate_the_deviation() { // idempotent re-assign
        Actor admin = adminWithPolicy();
        UUID target = target();
        rbac.assignRole(req(admin, "admin_iam.Role.Assign", target, 0), AdminRole.OPS_EXECUTIVE);
        rbac.assignRole(req(admin, "admin_iam.Role.Assign", target, 0), AdminRole.TREASURY_AND_SETTLEMENT, "cover");
        rbac.assignRole(req(admin, "admin_iam.Role.Assign", target, 0), AdminRole.TREASURY_AND_SETTLEMENT, "cover again");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_deviation_log WHERE admin_user_id = ?",
                Integer.class, target)).isEqualTo(1);
    }

    @Test
    void a_non_super_admin_cannot_assign_roles() { // inherited authz still fires first
        Actor ops = activeAdminWithRole(AdminRole.OPS_EXECUTIVE);
        UUID target = target();

        assertThatThrownBy(() -> rbac.assignRole(
                req(ops, "admin_iam.Role.Assign", target, 0), AdminRole.CREDIT_REVIEWER))
                .isInstanceOf(CommandRejectedException.class)
                .satisfies(e -> assertThat(((CommandRejectedException) e).getErrorCode()).isEqualTo("role_not_held"));
        assertThat(assignedRoles(target)).isEmpty();
    }

    // --- helpers -------------------------------------------------------------------------------

    private Actor adminWithPolicy() {
        AdminBootstrap.Seeded seeded = bootstrap.seedSuperAdmin(email(), "Root", phone());
        sod.seedDefaultPolicy(seeded.adminUserId());
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

    private UUID target() {
        UUID identityId = auth.provisionIdentity("admin_user", email(), phone(), "Target");
        UUID adminUserId = Ids.newId();
        jdbc.update("INSERT INTO admin_user (admin_user_id, identity_id, email, display_name, status) "
                        + "VALUES (?, ?, ?, ?, 'invited')",
                adminUserId, identityId, "au-" + adminUserId + "@arthvritt.test", "Target");
        return adminUserId;
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

    private List<String> assignedRoles(UUID adminUserId) {
        return jdbc.queryForList(
                "SELECT role::text FROM admin_role_assignment WHERE admin_user_id = ? AND status = 'active'",
                String.class, adminUserId);
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
