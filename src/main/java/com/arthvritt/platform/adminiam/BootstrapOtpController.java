package com.arthvritt.platform.adminiam;

import com.arthvritt.platform.notification.StubNotifier;
import com.arthvritt.platform.shared.error.NotFoundException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * API-key-gated OTP peek — the non-dev counterpart of {@code GET /dev/last-otp}. Returns the login OTP the
 * in-process {@link StubNotifier} "sent" to any identity (the code is otherwise only stored hashed), so the
 * password → OTP → session login is scriptable in <b>any profile</b> before a real SMS provider exists —
 * notably right after {@link BootstrapAdminController} mints the first super_admin.
 *
 * <p>Works for <b>any email</b> (admin, investor, ack-user, …), gated by the same
 * {@code platform.bootstrap.api-key} as the rest of {@code /bootstrap/**} — the key is already root-level, so
 * this adds no new exposure. <b>Self-retiring:</b> once a real SMS/email {@code NotificationChannel} replaces
 * the stub at the Production gate, the {@link StubNotifier} bean is gone, so this returns a clear
 * "delivered via the real channel" 404 instead of a code — login then uses the actual handset/inbox.
 */
@RestController
@RequestMapping("/bootstrap")
public class BootstrapOtpController {

    private final BootstrapApiKeyGuard apiKey;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<StubNotifier> stubNotifier;

    public BootstrapOtpController(BootstrapApiKeyGuard apiKey, JdbcTemplate jdbc,
                                  ObjectProvider<StubNotifier> stubNotifier) {
        this.apiKey = apiKey;
        this.jdbc = jdbc;
        this.stubNotifier = stubNotifier;
    }

    @GetMapping("/last-otp")
    public Map<String, String> lastOtp(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestParam String email) {
        apiKey.requireValidKey(authorization);

        StubNotifier notifier = stubNotifier.getIfAvailable();
        if (notifier == null) {
            // A real SMS/email channel is wired — the OTP went to the actual recipient, not an in-memory stub.
            throw new NotFoundException("OTP peek is unavailable: a real notification channel is active — "
                    + "the code was delivered to " + email + " directly");
        }

        UUID identityId = jdbc.query("SELECT identity_id FROM auth_identity WHERE email = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, email);
        if (identityId == null) {
            throw new NotFoundException("no identity for email: " + email);
        }
        String code = notifier.lastCodeFor(identityId)
                .orElseThrow(() -> new NotFoundException("no OTP has been sent to " + email
                        + " — call POST /auth/login/password first"));
        return Map.of("email", email, "code", code);
    }
}
