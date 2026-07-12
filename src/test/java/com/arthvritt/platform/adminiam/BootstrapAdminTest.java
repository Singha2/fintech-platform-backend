package com.arthvritt.platform.adminiam;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Break-glass admin bootstrap over the API-key-gated {@code POST /bootstrap/admin-users}. Proves the key
 * gate (valid / wrong / missing) and that a bootstrapped admin is a real, active, <i>loginable</i> admin —
 * the seed of the trust chain, minted before any super_admin exists. Default test profile ⇒ the
 * {@code application.properties} default key applies.
 */
class BootstrapAdminTest extends AbstractEdgeHttpTest {

    private static final String KEY = "local-bootstrap-key-change-me"; // application.properties default
    private static final String PASSWORD = "BootPass123!";

    @Test
    void valid_key_provisions_an_active_loginable_super_admin() throws Exception {
        String email = "boot-" + UUID.randomUUID() + "@arthvritt.test";
        MvcResult res = mvc.perform(request(KEY, body(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.role").value("super_admin"))
                .andReturn();
        UUID adminUserId = UUID.fromString(node(res).get("admin_user_id").asText());

        // A real admin: active row + an active super_admin role assignment.
        assertThat(jdbc.queryForObject("SELECT status::text FROM admin_user WHERE admin_user_id = ?",
                String.class, adminUserId)).isEqualTo("active");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_role_assignment WHERE admin_user_id = ? "
                + "AND role = 'super_admin' AND status = 'active'", Integer.class, adminUserId)).isEqualTo(1);

        // And it can actually log in over the real WS-0 edge (password → SMS-OTP → session).
        UUID identityId = jdbc.queryForObject("SELECT identity_id FROM admin_user WHERE admin_user_id = ?",
                UUID.class, adminUserId);
        assertThat(loginOverHttp(email, identityId)).isNotBlank();
    }

    /** Drives the real login edge (password → SMS-OTP → session) for the bootstrapped admin. */
    private String loginOverHttp(String email, UUID identityId) throws Exception {
        MvcResult pw = mvc.perform(post("/auth/login/password").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        String challengeId = node(pw).get("challenge_id").asText();
        String code = notifier.lastCodeFor(identityId).orElseThrow();
        MvcResult otp = mvc.perform(post("/auth/login/verify-otp").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("challenge_id", challengeId, "code", code))))
                .andExpect(status().isOk()).andReturn();
        return node(otp).get("bearer").asText();
    }

    @Test
    void a_wrong_key_is_401() throws Exception {
        mvc.perform(request("not-the-key", body("x-" + UUID.randomUUID() + "@arthvritt.test")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error_code").value("unauthorized"));
    }

    @Test
    void a_missing_key_is_401() throws Exception {
        mvc.perform(post("/bootstrap/admin-users").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body("y-" + UUID.randomUUID() + "@arthvritt.test"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error_code").value("unauthorized"));
    }

    @Test
    void a_duplicate_email_is_a_clean_400() throws Exception {
        String email = "dup-" + UUID.randomUUID() + "@arthvritt.test";
        mvc.perform(request(KEY, body(email))).andExpect(status().isCreated());
        mvc.perform(request(KEY, body(email)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("validation_failed"));
    }

    // --- helpers -----------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder request(String key, Map<String, Object> body) throws Exception {
        return post("/bootstrap/admin-users")
                .header("Authorization", "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
    }

    private Map<String, Object> body(String email) {
        return Map.of("email", email, "display_name", "Boot Admin", "phone", phone(), "password", PASSWORD);
    }
}
