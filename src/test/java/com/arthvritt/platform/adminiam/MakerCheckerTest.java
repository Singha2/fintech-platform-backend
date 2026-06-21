package com.arthvritt.platform.adminiam;

import com.arthvritt.platform.command.CommandRejectedException;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.shared.error.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M4d invariant tests (see docs/modules/M4d-maker-checker.md §7): the record-level maker≠checker
 * primitive, proven on the four-eyes admin-disable flow (propose → approve). Commands run through the
 * real M4a gateway against Testcontainers.
 */
class MakerCheckerTest extends AbstractAdminIamTest {

    private static final String DISABLE_PROPOSED = "admin_iam.AdminUser.DisableProposed";

    @Autowired private AdminUserService adminUsers;
    @Autowired private MakerCheckerGate makerChecker;

    @Test
    void the_same_actor_cannot_approve_their_own_proposal() { // INV-1, INV-2 (check is on actor_id)
        Actor a = superAdminActor(); // a holds super_admin, yet still cannot be both maker and checker
        UUID target = activeAdmin();
        adminUsers.proposeDisableAdmin(req(a, "admin_iam.AdminUser.ProposeDisable", target));

        CommandResult<MakerCheckerOutcome> result =
                adminUsers.approveDisableAdmin(req(a, "admin_iam.AdminUser.ApproveDisable", target));

        assertThat(result.result()).isEqualTo(MakerCheckerOutcome.BLOCKED);
        assertThat(status(target)).isEqualTo("active"); // transition NOT applied
        // Blocked is a committed, audited outcome (unlike pre-auth rejects).
        assertThat(envelopes("admin_iam.MakerChecker.Blocked", target)).isEqualTo(1);
        assertThat(envelopes("admin_iam.MakerChecker.Approved", target)).isEqualTo(0);
    }

    @Test
    void a_distinct_checker_approves_and_the_transition_applies() { // INV-3
        Actor maker = superAdminActor();
        Actor checker = superAdminActor();
        UUID target = activeAdmin();
        adminUsers.proposeDisableAdmin(req(maker, "admin_iam.AdminUser.ProposeDisable", target));

        CommandResult<MakerCheckerOutcome> result =
                adminUsers.approveDisableAdmin(req(checker, "admin_iam.AdminUser.ApproveDisable", target));

        assertThat(result.result()).isEqualTo(MakerCheckerOutcome.APPROVED);
        assertThat(status(target)).isEqualTo("disabled");
        assertThat(envelopes("admin_iam.MakerChecker.Approved", target)).isEqualTo(1);
        assertThat(envelopes("admin_iam.AdminUser.Disabled", target)).isEqualTo(1);
    }

    @Test
    void a_second_open_proposal_is_rejected() { // no stacking (closes the interposed-proposal hole)
        Actor a = superAdminActor();
        Actor b = superAdminActor();
        UUID target = activeAdmin();
        adminUsers.proposeDisableAdmin(req(a, "admin_iam.AdminUser.ProposeDisable", target));

        assertThatThrownBy(() -> adminUsers.proposeDisableAdmin(req(b, "admin_iam.AdminUser.ProposeDisable", target)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void a_resolved_proposal_cannot_be_answered_again() { // evaluate is answer-aware
        Actor a = superAdminActor();
        UUID target = activeAdmin();
        adminUsers.proposeDisableAdmin(req(a, "admin_iam.AdminUser.ProposeDisable", target));
        adminUsers.approveDisableAdmin(req(a, "admin_iam.AdminUser.ApproveDisable", target)); // blocked (same actor)

        // The proposal is now answered (Blocked); a fresh approve finds no open proposal.
        Actor b = superAdminActor();
        assertThatThrownBy(() -> adminUsers.approveDisableAdmin(req(b, "admin_iam.AdminUser.ApproveDisable", target)))
                .isInstanceOf(ValidationException.class);
        assertThat(status(target)).isEqualTo("active");
    }

    @Test
    void approving_with_no_proposal_is_rejected() { // INV-4
        Actor checker = superAdminActor();
        UUID target = activeAdmin();

        assertThatThrownBy(() -> adminUsers.approveDisableAdmin(
                req(checker, "admin_iam.AdminUser.ApproveDisable", target)))
                .isInstanceOf(ValidationException.class);
        assertThat(status(target)).isEqualTo("active");
    }

    @Test
    void the_queue_shows_a_pending_proposal_to_others_but_not_to_the_maker() { // INV-5
        Actor maker = superAdminActor();
        Actor other = superAdminActor();
        UUID target = activeAdmin();
        adminUsers.proposeDisableAdmin(req(maker, "admin_iam.AdminUser.ProposeDisable", target));

        assertThat(makerChecker.pendingApprovals(DISABLE_PROPOSED, other.identityId()))
                .extracting(MakerCheckerGate.PendingApproval::aggregateId).contains(target);
        assertThat(makerChecker.pendingApprovals(DISABLE_PROPOSED, maker.identityId()))
                .extracting(MakerCheckerGate.PendingApproval::aggregateId).doesNotContain(target);
    }

    @Test
    void a_non_super_admin_cannot_approve() { // INV-6 (inherited authz fires first)
        Actor maker = superAdminActor();
        UUID target = activeAdmin();
        adminUsers.proposeDisableAdmin(req(maker, "admin_iam.AdminUser.ProposeDisable", target));
        Actor ops = activeAdminWithRole(AdminRole.OPS_EXECUTIVE);

        assertThatThrownBy(() -> adminUsers.approveDisableAdmin(
                req(ops, "admin_iam.AdminUser.ApproveDisable", target)))
                .isInstanceOf(CommandRejectedException.class)
                .satisfies(e -> assertThat(((CommandRejectedException) e).getErrorCode()).isEqualTo("role_not_held"));
        assertThat(status(target)).isEqualTo("active");
    }

}
