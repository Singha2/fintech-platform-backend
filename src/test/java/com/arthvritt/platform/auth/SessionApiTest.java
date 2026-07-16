package com.arthvritt.platform.auth;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-1 · {@code GET /auth/session} — the "who am I" read that drives UI role-nav + MFA gating
 * (UI_INTEGRATION_BACKEND_SPEC §2). Additive read over the request principal + {@code auth_identity} +
 * {@code RoleResolver}; changes no command, no schema.
 *
 * <p>Load-bearing:
 * <ul>
 *   <li>an admin session reports its {@code kind}, active {@code roles}, {@code admin_user_id}, and a fresh MFA;</li>
 *   <li>a non-admin (investor) session reports {@code roles:[]} + {@code admin_user_id:null};</li>
 *   <li>the endpoint is authenticated-only — an anonymous caller is 401 (it must not fall under {@code /auth/login/**}).</li>
 * </ul>
 */
class SessionApiTest extends AbstractEdgeHttpTest {

    @Test
    void admin_session_reports_kind_roles_admin_user_id_and_fresh_mfa() throws Exception {
        Seeded admin = seedAdminWithRoles("ops_executive");
        String bearer = bearerFor(admin);

        MvcResult res = mvc.perform(get("/auth/session").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = node(res);

        assertThat(body.get("identity_id").asText()).isEqualTo(admin.identityId().toString());
        assertThat(body.get("kind").asText()).isEqualTo("admin_user");
        assertThat(body.get("email").asText()).isEqualToIgnoringCase(admin.email());
        assertThat(body.get("admin_user_id").asText()).isEqualTo(admin.adminUserId().toString());
        assertThat(body.get("mfa_fresh").asBoolean()).isTrue();   // fresh login → MFA-fresh
        assertThat(body.get("idle_expires_at").isNull()).isFalse();
        assertThat(body.get("absolute_expires_at").isNull()).isFalse();

        assertThat(body.get("roles").isArray()).isTrue();
        assertThat(rolesOf(body)).containsExactly("ops_executive");
    }

    @Test
    void admin_session_reports_all_held_roles() throws Exception {
        String bearer = bearerFor(seedAdminWithRoles("ops_executive", "compliance_reviewer"));

        JsonNode body = node(mvc.perform(get("/auth/session").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn());

        assertThat(rolesOf(body)).containsExactlyInAnyOrder("ops_executive", "compliance_reviewer");
    }

    @Test
    void non_admin_session_has_no_roles_and_no_admin_user_id() throws Exception {
        String bearer = bearerFor(seedLoginIdentity("investor"));

        JsonNode body = node(mvc.perform(get("/auth/session").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn());

        assertThat(body.get("kind").asText()).isEqualTo("investor");
        assertThat(body.get("roles").isArray()).isTrue();
        assertThat(body.get("roles")).isEmpty();
        assertThat(body.get("admin_user_id").isNull()).isTrue();
    }

    @Test
    void an_unauthenticated_caller_is_rejected() throws Exception {
        mvc.perform(get("/auth/session")).andExpect(status().isUnauthorized());
    }

    // --- helpers ----------------------------------------------------------------------------------------

    private static java.util.List<String> rolesOf(JsonNode body) {
        java.util.List<String> out = new java.util.ArrayList<>();
        body.get("roles").forEach(n -> out.add(n.asText()));
        return out;
    }
}
