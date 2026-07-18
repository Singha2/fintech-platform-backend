package com.arthvritt.platform.auth;

import com.arthvritt.platform.shared.error.ValidationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * WS-0 login edge (DL-BE-030): the only command-class routes that take no bearer — they mint the session.
 * Two steps mirroring the M3a flow: password → issue SMS-OTP, then verify-OTP → establish session. The
 * returned {@code bearer} is the {@code auth_session.session_id} (INV-1); subsequent calls carry it as
 * {@code Authorization: Bearer}. Thin adapter over {@link AuthService} + {@link SessionService}; no new
 * logic, no envelope minted here beyond what those services already audit.
 */
@RestController
@RequestMapping("/auth/login")
public class AuthController {

    private final AuthService auth;
    private final SessionService sessions;

    public AuthController(AuthService auth, SessionService sessions) {
        this.auth = auth;
        this.sessions = sessions;
    }

    @PostMapping("/password")
    public Map<String, String> password(@RequestBody(required = false) Map<String, String> body) {
        PasswordResult result = auth.authenticatePassword(required(body, "email"), required(body, "password"));
        if (!result.authenticated()) {
            throw new LoginFailedException();
        }
        UUID challengeId = auth.issueLoginOtp(result.identityId());
        return Map.of("challenge_id", challengeId.toString());
    }

    /**
     * BE-18 Part 1 (M11-B): passwordless investor login entry — open like {@code /password}, enumeration-safe
     * (DoR-1). {@code verify-otp} below is reused unchanged to establish the session.
     */
    @PostMapping("/investor/request-otp")
    public Map<String, String> investorRequestOtp(@RequestBody(required = false) Map<String, String> body) {
        UUID challengeId = auth.requestInvestorOtp(required(body, "email"));
        return Map.of("challenge_id", challengeId.toString());
    }

    @PostMapping("/verify-otp")
    public Map<String, String> verifyOtp(@RequestBody(required = false) Map<String, String> body) {
        OtpResult result = auth.verifyOtp(uuid(required(body, "challenge_id"), "challenge_id"),
                required(body, "code"));
        if (!result.verified()) {
            throw new LoginFailedException();
        }
        MfaAssertion assertion = result.assertion();
        // Tenant claims are derived from IAM (roles) — empty for WS-0; admin scoping is a Milestone-2 concern.
        UUID sessionId = sessions.establishSession(assertion.identityId(), assertion.assertionId(),
                TenantClaims.empty(), null, null);
        return Map.of("bearer", sessionId.toString());
    }

    /** A required body field — a missing/blank value is a 400 (B4), never an NPE/500 on an open route. */
    private static String required(Map<String, String> body, String field) {
        String value = body == null ? null : body.get(field);
        if (value == null || value.isBlank()) {
            throw new ValidationException("missing required field: " + field);
        }
        return value;
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("field '" + field + "' is not a valid id");
        }
    }
}
