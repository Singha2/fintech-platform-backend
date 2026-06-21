package com.arthvritt.platform.auth;

import com.arthvritt.platform.AbstractIntegrationTest;
import com.arthvritt.platform.notification.StubNotifier;
import com.arthvritt.platform.shared.Ids;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3b invariant tests (see docs/modules/M3b-sessions-and-mfa-freshness.md §7). Integration against
 * Testcontainers; real assertions are minted through {@link AuthService} so the freshness gate is
 * exercised end-to-end, then {@code consumed_at} is aged in-place to simulate the passage of time.
 */
class SessionServiceTest extends AbstractIntegrationTest {

    @Autowired private SessionService sessions;
    @Autowired private AuthService auth;
    @Autowired private StubNotifier notifier;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearNotifier() {
        notifier.clear();
    }

    @Test
    void establish_writes_active_row_with_assertion_claims_and_bounded_expiry() { // INV-1, INV-6
        UUID id = admin();
        MfaAssertion assertion = freshAssertion(id);
        TenantClaims claims = TenantClaims.forAdmin(java.util.List.of("ops_admin"));

        UUID sessionId = sessions.establishSession(id, assertion.assertionId(), claims, "203.0.113.7", "JUnit");

        assertThat(jdbc.queryForObject(
                "SELECT status::text FROM auth_session WHERE session_id = ?", String.class, sessionId))
                .isEqualTo("active");
        assertThat(jdbc.queryForObject(
                "SELECT mfa_assertion_id FROM auth_session WHERE session_id = ?", UUID.class, sessionId))
                .isEqualTo(assertion.assertionId());
        assertThat(jdbc.queryForObject(
                "SELECT tenant_claims->'roles'->>0 FROM auth_session WHERE session_id = ?", String.class, sessionId))
                .isEqualTo("ops_admin");
        assertThat(jdbc.queryForObject(
                "SELECT idle_expires_at <= absolute_expires_at FROM auth_session WHERE session_id = ?",
                Boolean.class, sessionId)).isTrue();
        // Establish + TenantClaim.Issued are both audited.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE aggregate_id = ? AND event_type = 'auth.Session.Established'",
                Integer.class, sessionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE aggregate_id = ? AND event_type = 'auth.TenantClaim.Issued'",
                Integer.class, sessionId)).isEqualTo(1);
    }

    @Test
    void establish_ties_both_envelopes_to_one_correlation_id() { // INV-6 — trace integrity
        UUID id = admin();
        UUID sessionId = sessions.establishSession(id, freshAssertion(id).assertionId(),
                TenantClaims.empty(), null, null);

        Integer distinctCorrelations = jdbc.queryForObject(
                "SELECT count(DISTINCT correlation_id) FROM sys_audit_event WHERE aggregate_id = ? "
                        + "AND event_type IN ('auth.Session.Established', 'auth.TenantClaim.Issued')",
                Integer.class, sessionId);
        assertThat(distinctCorrelations).isEqualTo(1);
    }

    @Test
    void resolve_within_idle_rolls_the_window_and_stays_active() { // INV-1
        UUID sessionId = establishFor(admin());
        // Age last_seen / idle backward (still inside the absolute ceiling) so the roll is observable.
        jdbc.update("UPDATE auth_session SET last_seen_at = now() - interval '5 minutes', "
                + "idle_expires_at = now() + interval '1 minute' WHERE session_id = ?", sessionId);

        SessionResolution r = sessions.resolveSession(sessionId);

        assertThat(r.active()).isTrue();
        assertThat(r.session().status()).isEqualTo("active");
        // Idle window pushed back out to ~30 min from now.
        assertThat(jdbc.queryForObject(
                "SELECT idle_expires_at > now() + interval '25 minutes' FROM auth_session WHERE session_id = ?",
                Boolean.class, sessionId)).isTrue();
    }

    @Test
    void resolve_past_idle_expiry_terminates_as_expired() { // INV-2
        UUID sessionId = establishFor(admin());
        jdbc.update("UPDATE auth_session SET idle_expires_at = now() - interval '1 second' "
                + "WHERE session_id = ?", sessionId);

        SessionResolution r = sessions.resolveSession(sessionId);

        assertThat(r.active()).isFalse();
        assertThat(r.reason()).isEqualTo("idle_expired");
        assertThat(jdbc.queryForObject(
                "SELECT status::text FROM auth_session WHERE session_id = ?", String.class, sessionId))
                .isEqualTo("expired");
        // A second resolve still terminal — expiry is one-way.
        assertThat(sessions.resolveSession(sessionId).active()).isFalse();
    }

    @Test
    void resolve_past_absolute_ceiling_terminates_as_expired() { // INV-1, INV-2
        UUID sessionId = establishFor(admin());
        // Shift the whole lifetime into the past (keeping absolute > issued and idle <= absolute).
        jdbc.update("UPDATE auth_session SET issued_at = now() - interval '9 hours', "
                + "idle_expires_at = now() - interval '1 minute', "
                + "absolute_expires_at = now() - interval '1 minute' WHERE session_id = ?", sessionId);

        SessionResolution r = sessions.resolveSession(sessionId);

        assertThat(r.active()).isFalse();
        assertThat(r.reason()).isEqualTo("absolute_expired");
    }

    @Test
    void revoke_marks_revoked_and_blocks_further_resolution() { // INV-2
        UUID sessionId = establishFor(admin());

        sessions.revokeSession(sessionId);

        assertThat(jdbc.queryForObject(
                "SELECT status::text FROM auth_session WHERE session_id = ?", String.class, sessionId))
                .isEqualTo("revoked");
        assertThat(jdbc.queryForObject(
                "SELECT revoked_at IS NOT NULL FROM auth_session WHERE session_id = ?", Boolean.class, sessionId))
                .isTrue();
        assertThat(sessions.resolveSession(sessionId).reason()).isEqualTo("revoked");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE aggregate_id = ? AND event_type = 'auth.Session.Revoked'",
                Integer.class, sessionId)).isEqualTo(1);
    }

    @Test
    void revoke_is_a_noop_on_an_already_terminal_session() { // INV-2 (idempotent logout)
        UUID sessionId = establishFor(admin());
        sessions.revokeSession(sessionId);

        sessions.revokeSession(sessionId); // second logout — no second envelope

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE aggregate_id = ? AND event_type = 'auth.Session.Revoked'",
                Integer.class, sessionId)).isEqualTo(1);
    }

    @Test
    void mfa_freshness_respects_the_sensitivity_window() { // INV-4
        UUID id = admin();
        MfaAssertion assertion = freshAssertion(id);
        UUID sessionId = sessions.establishSession(id, assertion.assertionId(), TenantClaims.empty(), null, null);
        AuthSession session = sessions.resolveSession(sessionId).session();

        // Just-consumed → fresh for both bands.
        assertThat(sessions.isMfaFresh(session, ActionSensitivity.SENSITIVE)).isTrue();
        assertThat(sessions.isMfaFresh(session, ActionSensitivity.NORMAL)).isTrue();

        // ~10 min old → stale for SENSITIVE (5 min), still fresh for NORMAL (30 min).
        ageAssertion(assertion.assertionId(), "10 minutes");
        assertThat(sessions.isMfaFresh(session, ActionSensitivity.SENSITIVE)).isFalse();
        assertThat(sessions.isMfaFresh(session, ActionSensitivity.NORMAL)).isTrue();

        // ~31 min old → stale for both.
        ageAssertion(assertion.assertionId(), "31 minutes");
        assertThat(sessions.isMfaFresh(session, ActionSensitivity.NORMAL)).isFalse();
    }

    @Test
    void mfa_freshness_is_false_without_an_assertion() { // INV-4
        AuthSession noAssertion = new AuthSession(Ids.newId(), Ids.newId(), "active",
                Instant.now(), Instant.now(), Instant.now(), Instant.now(), null, TenantClaims.empty());

        assertThat(sessions.isMfaFresh(noAssertion, ActionSensitivity.NORMAL)).isFalse();
    }

    @Test
    void tenant_claims_round_trip_through_resolution() { // INV-5
        UUID id = admin();
        UUID investorScoped = Ids.newId();
        UUID sessionId = sessions.establishSession(id, freshAssertion(id).assertionId(),
                TenantClaims.forInvestor(investorScoped), null, null);

        AuthSession session = sessions.resolveSession(sessionId).session();

        assertThat(session.tenantClaims().get("investor_id")).isEqualTo(investorScoped.toString());
    }

    @Test
    void db_rejects_idle_expiry_beyond_the_absolute_ceiling() { // DB invariant (app + DB both fire)
        UUID id = admin();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO auth_session (session_id, identity_id, idle_expires_at, absolute_expires_at) "
                        + "VALUES (?, ?, now() + interval '2 hours', now() + interval '1 hour')",
                Ids.newId(), id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("auth_session_idle_within_absolute");
    }

    // --- helpers -------------------------------------------------------------------------------

    private UUID admin() {
        return auth.provisionIdentity("admin_user", email(), "+919800000001", "Ops");
    }

    private MfaAssertion freshAssertion(UUID identityId) {
        UUID challengeId = auth.issueLoginOtp(identityId);
        return auth.verifyOtp(challengeId, notifier.lastCodeFor(identityId).orElseThrow()).assertion();
    }

    private UUID establishFor(UUID identityId) {
        return sessions.establishSession(identityId, freshAssertion(identityId).assertionId(),
                TenantClaims.empty(), null, null);
    }

    private void ageAssertion(UUID assertionId, String interval) {
        jdbc.update("UPDATE auth_otp_challenge SET consumed_at = now() - interval '" + interval + "' "
                + "WHERE assertion_id = ?", assertionId);
    }

    private static String email() {
        return "user-" + UUID.randomUUID() + "@arthvritt.test";
    }
}
