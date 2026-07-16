package com.arthvritt.platform.web;

import com.arthvritt.platform.AbstractIntegrationTest;
import com.arthvritt.platform.auth.AuthService;
import com.arthvritt.platform.notification.StubNotifier;
import com.arthvritt.platform.shared.Ids;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared base for HTTP edge tests (WS-0 onward): MockMvc over the Testcontainers Postgres, plus the
 * admin-seed + HTTP-login helpers every slice's test needs to obtain a bearer. Seeding writes an active
 * admin with a password directly (test fixture); the bearer is then obtained over the <i>real</i> login
 * endpoints (password → SMS-OTP → session), so every test drives the same WS-0 edge an operator would.
 */
@AutoConfigureMockMvc
public abstract class AbstractEdgeHttpTest extends AbstractIntegrationTest {

    private static final Random RND = new Random();

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected AuthService auth;
    @Autowired protected StubNotifier notifier;
    @Autowired protected JdbcTemplate jdbc;

    /** A seeded active admin holding all {@code roles}, with a password set so it can log in over HTTP. */
    protected Seeded seedAdminWithRoles(String... roles) {
        String email = "adm-" + UUID.randomUUID() + "@arthvritt.test";
        String password = "Pw-" + UUID.randomUUID();
        UUID identityId = auth.provisionIdentity("admin_user", email, phone(), "Admin");
        UUID adminUserId = Ids.newId();
        jdbc.update("INSERT INTO admin_user (admin_user_id, identity_id, email, display_name, status) "
                + "VALUES (?, ?, ?, ?, 'active')", adminUserId, identityId, email, "Admin");
        for (String role : roles) {
            jdbc.update("INSERT INTO admin_role_assignment (admin_user_id, role, status, assigned_by) "
                    + "VALUES (?, ?::admin_role, 'active', ?)", adminUserId, role, adminUserId);
        }
        auth.setPassword(identityId, password);
        return new Seeded(adminUserId, identityId, email, password);
    }

    /**
     * A non-admin login identity (password + phone) of the given {@code kind} — enough to obtain a session
     * bearer. No {@code admin_user} row and no roles, so {@code /auth/session} reports {@code roles:[]}.
     */
    protected Seeded seedLoginIdentity(String kind) {
        String email = kind + "-" + UUID.randomUUID() + "@arthvritt.test";
        String password = "Pw-" + UUID.randomUUID();
        UUID identityId = auth.provisionIdentity(kind, email, phone(), kind);
        auth.setPassword(identityId, password);
        return new Seeded(Ids.newId(), identityId, email, password);   // adminUserId unused for login
    }

    /** Logs the seeded admin in over HTTP (password → verify-otp) and returns the session bearer. */
    protected String bearerFor(Seeded admin) {
        try {
            MvcResult pw = mvc.perform(post("/auth/login/password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("email", admin.email(), "password", admin.password()))))
                    .andExpect(status().isOk()).andReturn();
            String challengeId = node(pw).get("challenge_id").asText();
            String code = notifier.lastCodeFor(admin.identityId()).orElseThrow();
            MvcResult otp = mvc.perform(post("/auth/login/verify-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("challenge_id", challengeId, "code", code))))
                    .andExpect(status().isOk()).andReturn();
            return node(otp).get("bearer").asText();
        } catch (Exception e) {
            throw new IllegalStateException("login failed for " + admin.email(), e);
        }
    }

    protected JsonNode node(MvcResult res) {
        try {
            return json.readTree(res.getResponse().getContentAsString());
        } catch (Exception e) {
            throw new IllegalStateException("unreadable response body", e);
        }
    }

    protected static String phone() {
        return "+9198" + (10_000_000 + RND.nextInt(89_999_999));
    }

    /** A seeded admin: its admin_user id, auth identity id, and login credentials. */
    protected record Seeded(UUID adminUserId, UUID identityId, String email, String password) {
    }
}
