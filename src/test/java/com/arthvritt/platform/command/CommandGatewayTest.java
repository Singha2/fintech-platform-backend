package com.arthvritt.platform.command;

import com.arthvritt.platform.AbstractIntegrationTest;
import com.arthvritt.platform.adminiam.AdminUserService;
import com.arthvritt.platform.auth.ActionSensitivity;
import com.arthvritt.platform.auth.AuthService;
import com.arthvritt.platform.auth.AuthSession;
import com.arthvritt.platform.auth.MfaAssertion;
import com.arthvritt.platform.auth.SessionService;
import com.arthvritt.platform.auth.TenantClaims;
import com.arthvritt.platform.notification.StubNotifier;
import com.arthvritt.platform.shared.Ids;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M4a invariant tests (see docs/modules/M4a-command-substrate.md §7). Written test-first against the
 * gateway API: idempotency (#4), MFA-freshness enforcement (#2), optimistic concurrency (P8), and the
 * command_id-stamped audit envelope (#5) — proven through the first real command, disableAdminUser.
 * Integration against Testcontainers.
 */
class CommandGatewayTest extends AbstractIntegrationTest {

    @Autowired private AdminUserService adminUsers;
    @Autowired private AuthService auth;
    @Autowired private SessionService sessions;
    @Autowired private StubNotifier notifier;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearNotifier() {
        notifier.clear();
    }

    @Test
    void disable_runs_once_recording_idempotency_and_a_stamped_envelope() { // INV-1, INV-5
        Actor actor = actor();
        UUID target = admin();
        CommandRequest req = disableReq(actor, target, 1, Ids.newId());

        CommandResult<Void> result = adminUsers.disableAdminUser(req);

        assertThat(result.replayed()).isFalse();
        assertThat(result.eventId()).isNotNull();
        assertThat(status(target)).isEqualTo("disabled");
        assertThat(version(target)).isEqualTo(2);
        // sys_command_log row links to the resulting envelope.
        assertThat(jdbc.queryForObject(
                "SELECT resulting_event_id FROM sys_command_log WHERE actor_id = ? AND command_id = ?",
                UUID.class, actor.identityId(), req.commandId())).isEqualTo(result.eventId());
        // envelope carries the command_id and the actor's mfa assertion.
        assertThat(jdbc.queryForObject(
                "SELECT command_id FROM sys_audit_event WHERE event_id = ?", UUID.class, result.eventId()))
                .isEqualTo(req.commandId());
        assertThat(jdbc.queryForObject(
                "SELECT actor->>'mfa_assertion_id' FROM sys_audit_event WHERE event_id = ?",
                String.class, result.eventId())).isEqualTo(actor.assertionId().toString());
    }

    @Test
    void replaying_the_same_command_id_is_a_noop_returning_the_original_event() { // INV-1
        Actor actor = actor();
        UUID target = admin();
        UUID commandId = Ids.newId();

        CommandResult<Void> first = adminUsers.disableAdminUser(disableReq(actor, target, 1, commandId));
        // Replay with the SAME command_id; expectedVersion is now stale, but replay short-circuits.
        CommandResult<Void> replay = adminUsers.disableAdminUser(disableReq(actor, target, 1, commandId));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.eventId()).isEqualTo(first.eventId());
        assertThat(version(target)).isEqualTo(2); // mutation ran exactly once
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE command_id = ?", Integer.class, commandId))
                .isEqualTo(1);
    }

    @Test
    void concurrent_duplicates_execute_once_and_replay_once() { // INV-1 race-safety
        Actor actor = actor();
        UUID target = admin();
        UUID commandId = Ids.newId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        long executed;
        try {
            Future<CommandResult<Void>> a = pool.submit(() -> adminUsers.disableAdminUser(disableReq(actor, target, 1, commandId)));
            Future<CommandResult<Void>> b = pool.submit(() -> adminUsers.disableAdminUser(disableReq(actor, target, 1, commandId)));
            executed = Stream.of(a.get(), b.get()).filter(r -> !r.replayed()).count();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            pool.shutdown();
        }

        assertThat(executed).isEqualTo(1);
        assertThat(version(target)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE command_id = ?", Integer.class, commandId))
                .isEqualTo(1);
    }

    @Test
    void missing_mfa_assertion_is_rejected_with_no_trace() { // INV-3
        Actor actor = actorWithoutMfa();
        UUID target = admin();
        CommandRequest req = disableReq(actor, target, 1, Ids.newId());

        assertThatThrownBy(() -> adminUsers.disableAdminUser(req))
                .isInstanceOf(CommandRejectedException.class)
                .satisfies(e -> assertThat(((CommandRejectedException) e).getErrorCode()).isEqualTo("mfa_assertion_missing"));

        assertNoTrace(target, actor, req.commandId());
    }

    @Test
    void stale_mfa_assertion_is_rejected_with_no_trace() { // INV-3
        Actor actor = actor();
        ageAssertion(actor.assertionId(), "31 minutes"); // past the SENSITIVE (5m) and NORMAL (30m) windows
        UUID target = admin();
        CommandRequest req = disableReq(actor, target, 1, Ids.newId());

        assertThatThrownBy(() -> adminUsers.disableAdminUser(req))
                .isInstanceOf(CommandRejectedException.class)
                .satisfies(e -> assertThat(((CommandRejectedException) e).getErrorCode()).isEqualTo("mfa_assertion_expired"));

        assertNoTrace(target, actor, req.commandId());
    }

    @Test
    void stale_aggregate_version_is_rejected_with_no_trace() { // INV-4
        Actor actor = actor();
        UUID target = admin();
        CommandRequest req = disableReq(actor, target, 99, Ids.newId()); // wrong expected version

        assertThatThrownBy(() -> adminUsers.disableAdminUser(req))
                .isInstanceOf(CommandRejectedException.class)
                .satisfies(e -> assertThat(((CommandRejectedException) e).getErrorCode()).isEqualTo("aggregate_version_stale"));

        assertThat(status(target)).isEqualTo("active");
        assertNoTrace(target, actor, req.commandId());
    }

    @Test
    void reusing_a_command_id_for_a_different_aggregate_is_an_idempotency_conflict() { // INV-2
        Actor actor = actor();
        UUID targetA = admin();
        UUID targetB = admin();
        UUID commandId = Ids.newId();

        adminUsers.disableAdminUser(disableReq(actor, targetA, 1, commandId));

        assertThatThrownBy(() -> adminUsers.disableAdminUser(disableReq(actor, targetB, 1, commandId)))
                .isInstanceOf(CommandRejectedException.class)
                .satisfies(e -> assertThat(((CommandRejectedException) e).getErrorCode()).isEqualTo("command_id_payload_mismatch"));

        assertThat(status(targetB)).isEqualTo("active"); // the divergent command never ran
    }

    @Test
    void a_session_less_command_is_rejected_cleanly_not_with_an_npe() { // INV-3 (null-session guard)
        UUID target = admin();
        CommandRequest req = new CommandRequest(null, Ids.newId(), "admin_iam",
                "admin_iam.AdminUser.Disable", "AdminUser", target, 1, "admin_user", ActionSensitivity.SENSITIVE);

        assertThatThrownBy(() -> adminUsers.disableAdminUser(req))
                .isInstanceOf(CommandRejectedException.class)
                .satisfies(e -> assertThat(((CommandRejectedException) e).getErrorCode()).isEqualTo("mfa_assertion_missing"));
        assertThat(status(target)).isEqualTo("active");
    }

    @Test
    void an_actor_without_an_admin_user_row_is_rejected() { // acting identity is not an admin (RBAC is M4b)
        UUID identityId = auth.provisionIdentity("admin_user", email(), "+919800000003", "Ghost");
        UUID assertionId = freshAssertion(identityId); // session + MFA, but no admin_user row inserted
        UUID sessionId = sessions.establishSession(identityId, assertionId, TenantClaims.empty(), null, null);
        AuthSession session = sessions.resolveSession(sessionId).session();
        UUID target = admin();
        CommandRequest req = new CommandRequest(session, Ids.newId(), "admin_iam",
                "admin_iam.AdminUser.Disable", "AdminUser", target, 1, "admin_user", ActionSensitivity.SENSITIVE);

        assertThatThrownBy(() -> adminUsers.disableAdminUser(req))
                .isInstanceOf(com.arthvritt.platform.shared.error.ValidationException.class);
        assertThat(status(target)).isEqualTo("active"); // target untouched; claim rolled back
    }

    // --- helpers -------------------------------------------------------------------------------

    /** An acting admin: identity + admin_user row + a session carrying a fresh MFA assertion. */
    private Actor actor() {
        return makeActor(true);
    }

    private Actor actorWithoutMfa() {
        return makeActor(false);
    }

    private Actor makeActor(boolean withMfa) {
        UUID identityId = auth.provisionIdentity("admin_user", email(), "+919800000001", "Actor");
        insertAdminUser(identityId);
        UUID assertionId = withMfa ? freshAssertion(identityId) : null;
        UUID sessionId = sessions.establishSession(identityId, assertionId, TenantClaims.empty(), null, null);
        AuthSession session = sessions.resolveSession(sessionId).session();
        return new Actor(identityId, session, assertionId);
    }

    /** A target admin_user row (active, version 1). */
    private UUID admin() {
        UUID identityId = auth.provisionIdentity("admin_user", email(), "+919800000002", "Target");
        return insertAdminUser(identityId);
    }

    private UUID insertAdminUser(UUID identityId) {
        UUID adminUserId = Ids.newId();
        jdbc.update("INSERT INTO admin_user (admin_user_id, identity_id, email, display_name, status) "
                        + "VALUES (?, ?, ?, ?, 'active')",
                adminUserId, identityId, "admin-" + adminUserId + "@arthvritt.test", "Admin");
        return adminUserId;
    }

    private UUID freshAssertion(UUID identityId) {
        UUID challengeId = auth.issueLoginOtp(identityId);
        MfaAssertion assertion = auth.verifyOtp(challengeId, notifier.lastCodeFor(identityId).orElseThrow()).assertion();
        return assertion.assertionId();
    }

    private void ageAssertion(UUID assertionId, String interval) {
        jdbc.update("UPDATE auth_otp_challenge SET consumed_at = now() - interval '" + interval + "' "
                + "WHERE assertion_id = ?", assertionId);
    }

    private CommandRequest disableReq(Actor actor, UUID target, int expectedVersion, UUID commandId) {
        return new CommandRequest(actor.session(), commandId, "admin_iam",
                "admin_iam.AdminUser.Disable", "AdminUser", target, expectedVersion,
                "admin_user", ActionSensitivity.SENSITIVE);
    }

    private void assertNoTrace(UUID target, Actor actor, UUID commandId) {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_command_log WHERE actor_id = ? AND command_id = ?",
                Integer.class, actor.identityId(), commandId)).isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE command_id = ?", Integer.class, commandId)).isEqualTo(0);
    }

    private String status(UUID adminUserId) {
        return jdbc.queryForObject("SELECT status::text FROM admin_user WHERE admin_user_id = ?",
                String.class, adminUserId);
    }

    private int version(UUID adminUserId) {
        return jdbc.queryForObject("SELECT aggregate_version FROM admin_user WHERE admin_user_id = ?",
                Integer.class, adminUserId);
    }

    private static String email() {
        return "user-" + UUID.randomUUID() + "@arthvritt.test";
    }

    private record Actor(UUID identityId, AuthSession session, UUID assertionId) {
    }
}
