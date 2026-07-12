package com.arthvritt.platform.adminiam;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The API-key-gated OTP peek ({@code GET /bootstrap/last-otp}) — proves the whole bootstrap → login flow is
 * driveable with only the bootstrap key, in any profile, before a real SMS provider exists: mint a super_admin,
 * password-step, peek the OTP the stub "sent", verify → a live session bearer. Plus the key gate and the
 * not-found paths.
 */
class BootstrapOtpTest extends AbstractEdgeHttpTest {

    private static final String KEY = "local-bootstrap-key-change-me"; // application.properties default
    private static final String PASSWORD = "BootPass123!";

    @Test
    void bootstrap_then_login_end_to_end_using_only_the_api_key() throws Exception {
        String email = "otp-" + UUID.randomUUID() + "@arthvritt.test";

        // 1. Bootstrap the first super_admin (API key, no session).
        mvc.perform(post("/bootstrap/admin-users").header("Authorization", "Bearer " + KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "display_name", "OTP Admin",
                                "phone", phone(), "password", PASSWORD))))
                .andExpect(status().isCreated());

        // 2. Password step → challenge_id.
        MvcResult pw = mvc.perform(post("/auth/login/password").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        String challengeId = node(pw).get("challenge_id").asText();

        // 3. Peek the OTP with the bootstrap key.
        MvcResult peek = mvc.perform(get("/bootstrap/last-otp").param("email", email)
                        .header("Authorization", "Bearer " + KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andReturn();
        String code = node(peek).get("code").asText();

        // 4. Verify → a live session bearer. The peeked code is the real one.
        MvcResult otp = mvc.perform(post("/auth/login/verify-otp").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("challenge_id", challengeId, "code", code))))
                .andExpect(status().isOk()).andReturn();
        assertThat(node(otp).get("bearer").asText()).isNotBlank();
    }

    @Test
    void a_wrong_key_is_401() throws Exception {
        mvc.perform(get("/bootstrap/last-otp").param("email", "whoever@arthvritt.test")
                        .header("Authorization", "Bearer not-the-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error_code").value("unauthorized"));
    }

    @Test
    void an_unknown_email_is_404() throws Exception {
        mvc.perform(get("/bootstrap/last-otp").param("email", "nobody-" + UUID.randomUUID() + "@arthvritt.test")
                        .header("Authorization", "Bearer " + KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error_code").value("not_found"));
    }

    @Test
    void an_identity_with_no_otp_sent_is_404() throws Exception {
        String email = "no-otp-" + UUID.randomUUID() + "@arthvritt.test";
        mvc.perform(post("/bootstrap/admin-users").header("Authorization", "Bearer " + KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "display_name", "No OTP",
                                "phone", phone(), "password", PASSWORD))))
                .andExpect(status().isCreated());
        // No password step yet → nothing sent.
        mvc.perform(get("/bootstrap/last-otp").param("email", email).header("Authorization", "Bearer " + KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error_code").value("not_found"));
    }
}
