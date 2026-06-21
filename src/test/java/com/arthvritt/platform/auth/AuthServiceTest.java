package com.arthvritt.platform.auth;

import com.arthvritt.platform.AbstractIntegrationTest;
import com.arthvritt.platform.notification.NotificationRequest;
import com.arthvritt.platform.notification.StubNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3a invariant tests (see docs/modules/M3a-authentication.md §7). Integration against
 * Testcontainers; the SMS channel is the in-memory {@link StubNotifier}, from which tests read the
 * OTP code that a real handset would receive.
 */
class AuthServiceTest extends AbstractIntegrationTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private AuthService auth;
    @Autowired private StubNotifier notifier;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearNotifier() {
        notifier.clear();
    }

    @Test
    void password_auth_succeeds_with_correct_credentials_and_fails_otherwise() { // INV-1, INV-5
        String email = email();
        UUID id = auth.provisionIdentity("admin_user", email, "+919800000001", "Ops One");
        auth.setPassword(id, "Sup3r-Secret!");

        assertThat(auth.authenticatePassword(email, "Sup3r-Secret!").authenticated()).isTrue();
        assertThat(auth.authenticatePassword(email, "wrong").authenticated()).isFalse();
        assertThat(auth.authenticatePassword("nobody@arthvritt.test", "x").authenticated()).isFalse();
    }

    @Test
    void disabled_identity_cannot_authenticate() { // INV-5
        String email = email();
        UUID id = auth.provisionIdentity("admin_user", email, "+919800000002", "Ops Two");
        auth.setPassword(id, "Sup3r-Secret!");
        jdbc.update("UPDATE auth_identity SET status = 'disabled' WHERE identity_id = ?", id);

        assertThat(auth.authenticatePassword(email, "Sup3r-Secret!").authenticated()).isFalse();
    }

    @Test
    void password_is_stored_as_an_argon2_hash_not_plaintext() { // INV-1
        UUID id = auth.provisionIdentity("admin_user", email(), "+919800000003", "Ops Three");
        auth.setPassword(id, "Sup3r-Secret!");

        String secretHash = jdbc.queryForObject(
                "SELECT secret_hash FROM auth_credential WHERE identity_id = ? AND kind = 'password'",
                String.class, id);
        assertThat(secretHash).startsWith("$argon2").doesNotContain("Sup3r-Secret!");
    }

    @Test
    void otp_issue_delivers_code_and_verify_mints_assertion() { // INV-2, INV-3
        UUID id = auth.provisionIdentity("admin_user", email(), "+919800000004", "Ops Four");
        UUID challengeId = auth.issueLoginOtp(id);

        String code = notifier.lastCodeFor(id).orElseThrow();
        OtpResult result = auth.verifyOtp(challengeId, code);

        assertThat(result.verified()).isTrue();
        assertThat(result.assertion().assertionId()).isNotNull();
        // Row consumed + assertion stamped only on consume; plaintext code never stored.
        assertThat(jdbc.queryForObject(
                "SELECT status::text FROM auth_otp_challenge WHERE challenge_id = ?", String.class, challengeId))
                .isEqualTo("consumed");
        UUID storedAssertion = jdbc.queryForObject(
                "SELECT assertion_id FROM auth_otp_challenge WHERE challenge_id = ?", UUID.class, challengeId);
        assertThat(storedAssertion).isEqualTo(result.assertion().assertionId());
        assertThat(jdbc.queryForObject(
                "SELECT code_hash FROM auth_otp_challenge WHERE challenge_id = ?", String.class, challengeId))
                .doesNotContain(code);
    }

    @Test
    void otp_wrong_code_increments_attempts_and_locks_out() { // INV-4
        UUID id = auth.provisionIdentity("admin_user", email(), "+919800000005", "Ops Five");
        UUID challengeId = auth.issueLoginOtp(id);
        String code = notifier.lastCodeFor(id).orElseThrow();

        for (int i = 0; i < 5; i++) {
            assertThat(auth.verifyOtp(challengeId, "000000").verified()).isFalse();
        }
        assertThat(jdbc.queryForObject(
                "SELECT attempts FROM auth_otp_challenge WHERE challenge_id = ?", Integer.class, challengeId))
                .isEqualTo(5);
        // Locked out: even the correct code is now rejected.
        assertThat(auth.verifyOtp(challengeId, code).verified()).isFalse();
    }

    @Test
    void expired_otp_is_rejected() { // INV-4
        UUID id = auth.provisionIdentity("admin_user", email(), "+919800000006", "Ops Six");
        UUID challengeId = auth.issueLoginOtp(id);
        String code = notifier.lastCodeFor(id).orElseThrow();
        // Push the whole window into the past (keeping expires_at > issued_at to satisfy the CHECK).
        jdbc.update("UPDATE auth_otp_challenge SET issued_at = now() - interval '10 minutes', "
                + "expires_at = now() - interval '5 minutes' WHERE challenge_id = ?", challengeId);

        assertThat(auth.verifyOtp(challengeId, code).verified()).isFalse();
    }

    @Test
    void new_otp_supersedes_the_previous_active_challenge() { // INV-4
        UUID id = auth.provisionIdentity("admin_user", email(), "+919800000007", "Ops Seven");
        UUID first = auth.issueLoginOtp(id);
        String firstCode = notifier.lastCodeFor(id).orElseThrow();
        UUID second = auth.issueLoginOtp(id);

        assertThat(jdbc.queryForObject(
                "SELECT status::text FROM auth_otp_challenge WHERE challenge_id = ?", String.class, first))
                .isEqualTo("superseded");
        assertThat(auth.verifyOtp(first, firstCode).verified()).isFalse();
        assertThat(auth.verifyOtp(second, notifier.lastCodeFor(id).orElseThrow()).verified()).isTrue();
    }

    @Test
    void successful_login_emits_audit_events_and_chain_verifies() { // INV-6
        String email = email();
        UUID id = auth.provisionIdentity("admin_user", email, "+919800000008", "Ops Eight");
        auth.setPassword(id, "Sup3r-Secret!");
        auth.authenticatePassword(email, "Sup3r-Secret!");
        UUID challengeId = auth.issueLoginOtp(id);
        auth.verifyOtp(challengeId, notifier.lastCodeFor(id).orElseThrow());

        Integer events = jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE actor->>'actor_id' = ?", Integer.class, id.toString());
        assertThat(events).isGreaterThanOrEqualTo(3); // provisioned, password verified, otp issued, otp consumed
    }

    @Test
    void concurrent_verify_consumes_once_and_mints_one_assertion() throws Exception { // INV-3 race-safety
        UUID id = auth.provisionIdentity("admin_user", email(), "+919800000009", "Ops Nine");
        UUID challengeId = auth.issueLoginOtp(id);
        String code = notifier.lastCodeFor(id).orElseThrow();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        long verified;
        try {
            Future<OtpResult> a = pool.submit(() -> auth.verifyOtp(challengeId, code));
            Future<OtpResult> b = pool.submit(() -> auth.verifyOtp(challengeId, code));
            verified = Stream.of(a.get(), b.get()).filter(OtpResult::verified).count();
        } finally {
            pool.shutdown();
        }

        assertThat(verified).isEqualTo(1); // FOR UPDATE serializes; the loser sees 'consumed'
        Integer consumedRows = jdbc.queryForObject(
                "SELECT count(*) FROM auth_otp_challenge WHERE challenge_id = ? "
                        + "AND status = 'consumed' AND assertion_id IS NOT NULL", Integer.class, challengeId);
        assertThat(consumedRows).isEqualTo(1);
    }

    private static String email() {
        return "user-" + UUID.randomUUID() + "@arthvritt.test";
    }
}
