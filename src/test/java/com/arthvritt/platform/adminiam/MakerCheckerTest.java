package com.arthvritt.platform.adminiam;

import com.arthvritt.platform.AbstractIntegrationTest;
import com.arthvritt.platform.auth.ActionSensitivity;
import com.arthvritt.platform.auth.AuthService;
import com.arthvritt.platform.auth.AuthSession;
import com.arthvritt.platform.auth.SessionService;
import com.arthvritt.platform.auth.TenantClaims;
import com.arthvritt.platform.command.CommandRejectedException;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.notification.StubNotifier;
import com.arthvritt.platform.shared.Ids;
import com.arthvritt.platform.shared.crypto.SecretCipher;
import com.arthvritt.platform.shared.error.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M4d invariant tests (see docs/modules/M4d-maker-checker.md §7): the record-level maker≠checker
 * primitive, proven on the four-eyes admin-disable flow (propose → approve). Commands run through the
 * real M4a gateway against Testcontainers.
 */
class MakerCheckerTest extends AbstractIntegrationTest {

    private static final String DISABLE_PROPOSED = "admin_iam.AdminUser.DisableProposed";

    @Autowired private AdminUserService adminUsers;
    @Autowired private MakerCheckerGate makerChecker;
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
    void the_same_actor_cannot_approve_their_own_proposal() { // INV-1, INV-2 (check is on actor_id)
        Actor a = superAdmin(); // a holds super_admin, yet still cannot be both maker and checker
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
        Actor maker = superAdmin();
        Actor checker = superAdmin();
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
        Actor a = superAdmin();
        Actor b = superAdmin();
        UUID target = activeAdmin();
        adminUsers.proposeDisableAdmin(req(a, "admin_iam.AdminUser.ProposeDisable", target));

        assertThatThrownBy(() -> adminUsers.proposeDisableAdmin(req(b, "admin_iam.AdminUser.ProposeDisable", target)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void a_resolved_proposal_cannot_be_answered_again() { // evaluate is answer-aware
        Actor a = superAdmin();
        UUID target = activeAdmin();
        adminUsers.proposeDisableAdmin(req(a, "admin_iam.AdminUser.ProposeDisable", target));
        adminUsers.approveDisableAdmin(req(a, "admin_iam.AdminUser.ApproveDisable", target)); // blocked (same actor)

        // The proposal is now answered (Blocked); a fresh approve finds no open proposal.
        Actor b = superAdmin();
        assertThatThrownBy(() -> adminUsers.approveDisableAdmin(req(b, "admin_iam.AdminUser.ApproveDisable", target)))
                .isInstanceOf(ValidationException.class);
        assertThat(status(target)).isEqualTo("active");
    }

    @Test
    void approving_with_no_proposal_is_rejected() { // INV-4
        Actor checker = superAdmin();
        UUID target = activeAdmin();

        assertThatThrownBy(() -> adminUsers.approveDisableAdmin(
                req(checker, "admin_iam.AdminUser.ApproveDisable", target)))
                .isInstanceOf(ValidationException.class);
        assertThat(status(target)).isEqualTo("active");
    }

    @Test
    void the_queue_shows_a_pending_proposal_to_others_but_not_to_the_maker() { // INV-5
        Actor maker = superAdmin();
        Actor other = superAdmin();
        UUID target = activeAdmin();
        adminUsers.proposeDisableAdmin(req(maker, "admin_iam.AdminUser.ProposeDisable", target));

        assertThat(makerChecker.pendingApprovals(DISABLE_PROPOSED, other.identityId()))
                .extracting(MakerCheckerGate.PendingApproval::aggregateId).contains(target);
        assertThat(makerChecker.pendingApprovals(DISABLE_PROPOSED, maker.identityId()))
                .extracting(MakerCheckerGate.PendingApproval::aggregateId).doesNotContain(target);
    }

    @Test
    void a_non_super_admin_cannot_approve() { // INV-6 (inherited authz fires first)
        Actor maker = superAdmin();
        UUID target = activeAdmin();
        adminUsers.proposeDisableAdmin(req(maker, "admin_iam.AdminUser.ProposeDisable", target));
        Actor ops = activeAdminWithRole(AdminRole.OPS_EXECUTIVE);

        assertThatThrownBy(() -> adminUsers.approveDisableAdmin(
                req(ops, "admin_iam.AdminUser.ApproveDisable", target)))
                .isInstanceOf(CommandRejectedException.class)
                .satisfies(e -> assertThat(((CommandRejectedException) e).getErrorCode()).isEqualTo("role_not_held"));
        assertThat(status(target)).isEqualTo("active");
    }

    // --- helpers -------------------------------------------------------------------------------

    private Actor superAdmin() {
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

    /** An active admin_user that is the target of a disable proposal (no role / session needed). */
    private UUID activeAdmin() {
        UUID identityId = auth.provisionIdentity("admin_user", email(), phone(), "Target");
        UUID adminUserId = Ids.newId();
        jdbc.update("INSERT INTO admin_user (admin_user_id, identity_id, email, display_name, status) "
                        + "VALUES (?, ?, ?, ?, 'active')",
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

    private CommandRequest req(Actor actor, String commandType, UUID target) {
        return new CommandRequest(actor.session(), Ids.newId(), "admin_iam", commandType, "AdminUser",
                target, 0, "admin_user", ActionSensitivity.SENSITIVE);
    }

    private int envelopes(String eventType, UUID aggregateId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, eventType, aggregateId);
    }

    private String status(UUID adminUserId) {
        return jdbc.queryForObject("SELECT status::text FROM admin_user WHERE admin_user_id = ?",
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
