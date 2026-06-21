package com.arthvritt.platform.adminiam;

import com.arthvritt.platform.command.CommandRejectedException;
import com.arthvritt.platform.shared.Ids;
import com.arthvritt.platform.shared.error.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M4c invariant tests (see docs/modules/M4c-sod-enforcement.md §7): the two-tier SoD engine on
 * assignRole — strict block, soft-warn-with-deviation, rules-as-data policy supersession, and the
 * once-only quarterly review. Commands run through the real M4a gateway against Testcontainers.
 */
class SodEnforcementTest extends AbstractAdminIamTest {

    @Autowired private RbacService rbac;
    @Autowired private SodPolicyService sod;

    @Test
    void a_strict_pair_is_system_blocked_with_no_envelope() { // INV-1
        Actor admin = adminWithPolicy();
        UUID target = invitedAdmin();
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
        UUID target = invitedAdmin();
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

        UUID target = invitedAdmin();
        rbac.assignRole(req(admin, "admin_iam.Role.Assign", target, 0), AdminRole.CREDIT_REVIEWER);
        rbac.assignRole(req(admin, "admin_iam.Role.Assign", target, 0), AdminRole.TREASURY_AND_SETTLEMENT);
        assertThat(assignedRoles(target)).containsExactlyInAnyOrder("credit_reviewer", "treasury_and_settlement");
    }

    @Test
    void a_deviation_is_reviewed_exactly_once() { // INV-4
        Actor admin = adminWithPolicy();
        UUID target = invitedAdmin();
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
        UUID target = invitedAdmin();
        rbac.assignRole(req(admin, "admin_iam.Role.Assign", target, 0), AdminRole.CREDIT_REVIEWER);
        assertThatThrownBy(() -> rbac.assignRole(
                req(admin, "admin_iam.Role.Assign", target, 0), AdminRole.TREASURY_AND_SETTLEMENT))
                .isInstanceOf(CommandRejectedException.class);
    }

    @Test
    void re_assigning_a_held_soft_role_does_not_duplicate_the_deviation() { // idempotent re-assign
        Actor admin = adminWithPolicy();
        UUID target = invitedAdmin();
        rbac.assignRole(req(admin, "admin_iam.Role.Assign", target, 0), AdminRole.OPS_EXECUTIVE);
        rbac.assignRole(req(admin, "admin_iam.Role.Assign", target, 0), AdminRole.TREASURY_AND_SETTLEMENT, "cover");
        rbac.assignRole(req(admin, "admin_iam.Role.Assign", target, 0), AdminRole.TREASURY_AND_SETTLEMENT, "cover again");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_deviation_log WHERE admin_user_id = ?",
                Integer.class, target)).isEqualTo(1);
    }

    @Test
    void a_non_super_admin_cannot_assign_roles() { // inherited authz still fires first
        Actor ops = activeAdminWithRole(AdminRole.OPS_EXECUTIVE);
        UUID target = invitedAdmin();

        assertThatThrownBy(() -> rbac.assignRole(
                req(ops, "admin_iam.Role.Assign", target, 0), AdminRole.CREDIT_REVIEWER))
                .isInstanceOf(CommandRejectedException.class)
                .satisfies(e -> assertThat(((CommandRejectedException) e).getErrorCode()).isEqualTo("role_not_held"));
        assertThat(assignedRoles(target)).isEmpty();
    }

    // --- helpers (slice-specific; shared ones are in AbstractAdminIamTest) ---------------------

    /** A super-admin actor with the Phase-1 SoD policy seeded. */
    private Actor adminWithPolicy() {
        Actor admin = superAdminActor();
        sod.seedDefaultPolicy(admin.adminUserId());
        return admin;
    }

    private List<String> assignedRoles(UUID adminUserId) {
        return jdbc.queryForList(
                "SELECT role::text FROM admin_role_assignment WHERE admin_user_id = ? AND status = 'active'",
                String.class, adminUserId);
    }
}
