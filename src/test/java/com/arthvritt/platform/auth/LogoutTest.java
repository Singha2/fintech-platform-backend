package com.arthvritt.platform.auth;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DL-BE-089 — {@code POST /auth/logout} server-side session revoke. The endpoint terminates the session so the
 * bearer stops working immediately (not just a client-side token clear). Thin adapter over the existing
 * {@link SessionService#revokeSession} (idempotent, audits {@code auth.Session.Revoked}). The bearer <i>is</i> the
 * {@code auth_session.session_id}.
 */
class LogoutTest extends AbstractEdgeHttpTest {

    @Test
    void logout_revokes_the_session_server_side() throws Exception {
        String bearer = bearerFor(seedAdminWithRoles("ops_executive"));
        mvc.perform(get("/auth/session").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()); // live before logout

        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isNoContent());

        // The same bearer no longer authenticates (session terminated).
        mvc.perform(get("/auth/session").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_event "
                        + "WHERE event_type = 'auth.Session.Revoked' AND aggregate_id = ?",
                Integer.class, UUID.fromString(bearer))).isEqualTo(1);
    }

    @Test
    void a_second_logout_with_a_revoked_bearer_is_clean() throws Exception { // idempotent — no 500
        String bearer = bearerFor(seedAdminWithRoles("ops_executive"));
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isNoContent());
        // The revoked bearer is rejected by the security filter before the handler → 401, never a 5xx.
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_works_for_an_investor_session() throws Exception { // both kinds (DoD #4)
        String bearer = bearerFor(seedActiveInvestorWithLogin().login());
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isNoContent());
        mvc.perform(get("/auth/session").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isUnauthorized());
    }
}
