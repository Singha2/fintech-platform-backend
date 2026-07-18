package com.arthvritt.platform.auth;

import com.arthvritt.platform.adminiam.RoleResolver;
import com.arthvritt.platform.investor.InvestorQueryPort;
import com.arthvritt.platform.shared.error.NotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * BE-1 (UI_INTEGRATION_BACKEND_SPEC) — the "who am I" read the UI needs to drive role-based navigation and
 * MFA gating. Purely additive: assembles the request principal ({@link AuthSession}), the identity's
 * {@code auth_identity.kind}/{@code email}, the roles from {@link RoleResolver#activeRoles} (the <i>same</i>
 * resolver {@code CommandGateway} authorises with), and MFA-freshness from {@link SessionService#isMfaFresh}.
 * No new query logic, no authz change. M10-D (SES-1) adds a nullable {@code investor_id}, resolved per-request
 * via {@link InvestorQueryPort} — non-null only for {@code kind='investor'}.
 *
 * <p>Authenticated-only (any live bearer): {@code /auth/session} is deliberately <b>not</b> under the
 * {@code permitAll("/auth/login/**")} matcher, so the security chain's {@code anyRequest().authenticated()}
 * rule applies — an anonymous caller gets 401.
 */
@RestController
public class SessionController {

    private final JdbcTemplate jdbc;
    private final RoleResolver roles;
    private final SessionService sessions;
    private final InvestorQueryPort investors;

    public SessionController(JdbcTemplate jdbc, RoleResolver roles, SessionService sessions,
                             InvestorQueryPort investors) {
        this.jdbc = jdbc;
        this.roles = roles;
        this.sessions = sessions;
        this.investors = investors;
    }

    @GetMapping("/auth/session")
    public Map<String, Object> session(@AuthenticationPrincipal AuthSession session) {
        UUID identityId = session.identityId();

        Map<String, String> identity = jdbc.query(
                "SELECT kind::text AS kind, email FROM auth_identity WHERE identity_id = ?",
                rs -> rs.next() ? Map.of("kind", rs.getString("kind"), "email", rs.getString("email")) : null,
                identityId);
        if (identity == null) {
            throw new NotFoundException("identity not found: " + identityId);
        }

        // Nullable — only admin identities back an admin_user row (RoleResolver.adminUserId throws, so read directly).
        UUID adminUserId = jdbc.query("SELECT admin_user_id FROM admin_user WHERE identity_id = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, identityId);

        // Nullable — non-null only for kind='investor' (M10-D SES-1); server-resolved, never client-supplied.
        UUID investorId = investors.investorIdForIdentity(identityId).orElse(null);

        List<String> activeRoles = new ArrayList<>(new TreeSet<>(roles.activeRoles(identityId)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("identity_id", identityId.toString());
        body.put("kind", identity.get("kind"));
        body.put("email", identity.get("email"));
        body.put("roles", activeRoles);
        body.put("admin_user_id", adminUserId == null ? null : adminUserId.toString());
        body.put("investor_id", investorId == null ? null : investorId.toString());
        body.put("mfa_fresh", sessions.isMfaFresh(session, ActionSensitivity.SENSITIVE));
        body.put("idle_expires_at", session.idleExpiresAt() == null ? null : session.idleExpiresAt().toString());
        body.put("absolute_expires_at",
                session.absoluteExpiresAt() == null ? null : session.absoluteExpiresAt().toString());
        return body;
    }
}
