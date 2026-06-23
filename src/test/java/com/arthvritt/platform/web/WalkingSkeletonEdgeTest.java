package com.arthvritt.platform.web;

import com.arthvritt.platform.AbstractIntegrationTest;
import com.arthvritt.platform.adminiam.AdminBootstrap;
import com.arthvritt.platform.auth.AuthService;
import com.arthvritt.platform.notification.StubNotifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WS-0 edge tests (see docs/modules/WS-0-http-edge.md §7): the B4 command surface over real controllers.
 * Drives the foundation's already-built services through HTTP — login → command → query — and asserts the
 * wire contract: the request envelope, the {@code emitted_events} body, audit-before-2xx, idempotency, and
 * the no-envelope reject taxonomy. MockMvc over the Testcontainers Postgres.
 */
@AutoConfigureMockMvc
class WalkingSkeletonEdgeTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private AuthService auth;
    @Autowired private AdminBootstrap bootstrap;
    @Autowired private StubNotifier notifier;
    @Autowired private JdbcTemplate jdbc;

    private SuperAdmin admin;

    @BeforeEach
    void seedSuperAdmin() {
        notifier.clear();
        String email = "root-" + UUID.randomUUID() + "@arthvritt.test";
        String password = "Sup3r-Secret-" + UUID.randomUUID();
        AdminBootstrap.Seeded seeded = bootstrap.seedSuperAdmin(email, "Root", phone());
        auth.setPassword(seeded.identityId(), password);
        admin = new SuperAdmin(seeded.adminUserId(), seeded.identityId(), email, password);
    }

    // --- the headline edge invariants --------------------------------------------------------------

    @Test
    void no_bearer_on_a_protected_route_is_401_with_no_envelope() throws Exception {
        long before = auditCount();
        mvc.perform(get("/admin-users/{id}", admin.adminUserId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error_code").value("bearer_missing"))
                .andExpect(jsonPath("$.error_category").value("auth_failure"));
        assertThat(auditCount()).isEqualTo(before); // pre-authorisation failure: no audit fact (B2 §5.6)
    }

    @Test
    void login_round_trip_yields_a_usable_bearer() throws Exception {
        String bearer = login();
        mvc.perform(get("/admin-users/{id}", admin.adminUserId()).header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admin_user_id").value(admin.adminUserId().toString()))
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void provision_returns_201_with_emitted_events_and_the_envelope_is_durable() throws Exception {
        String bearer = login();
        MvcResult res = mvc.perform(provision(bearer, UUID.randomUUID(), newEmail()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.aggregate_id").isNotEmpty())
                .andExpect(jsonPath("$.emitted_events[0].event_type").value("admin_iam.AdminUser.Created"))
                .andReturn();

        // audit-before-2xx (X13): the event_id the response named is already persisted.
        String eventId = node(res).get("emitted_events").get(0).get("event_id").asText();
        Integer found = jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE event_id = ?", Integer.class, UUID.fromString(eventId));
        assertThat(found).isEqualTo(1);
    }

    @Test
    void replaying_a_command_id_returns_the_original_events_and_appends_no_second_envelope() throws Exception {
        String bearer = login();
        UUID commandId = UUID.randomUUID();
        String email = newEmail();
        MvcResult first = mvc.perform(provision(bearer, commandId, email))
                .andExpect(status().isCreated()).andReturn();
        String firstEventId = node(first).get("emitted_events").get(0).get("event_id").asText();
        UUID aggregateId = UUID.fromString(node(first).get("aggregate_id").asText());

        // Same command_id, same body → original events, no re-execution (B4 §2.4).
        MvcResult replay = mvc.perform(provision(bearer, commandId, email))
                .andExpect(status().isOk()).andReturn();
        assertThat(node(replay).get("emitted_events").get(0).get("event_id").asText()).isEqualTo(firstEventId);
        // No second envelope for this aggregate, and exactly one admin created (not two).
        assertThat(envelopesFor("admin_iam.AdminUser.Created", aggregateId)).isEqualTo(1);
        assertThat(countCreatedFor(email)).isEqualTo(1);
    }

    @Test
    void same_command_id_with_a_different_body_is_409_idempotency_conflict() throws Exception {
        String bearer = login();
        UUID commandId = UUID.randomUUID();
        mvc.perform(provision(bearer, commandId, newEmail())).andExpect(status().isCreated());
        mvc.perform(provision(bearer, commandId, newEmail()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("command_id_payload_mismatch"));
    }

    @Test
    void disable_with_a_stale_aggregate_version_is_409_version_conflict() throws Exception {
        String bearer = login();
        UUID target = activeAdmin();
        mvc.perform(post("/admin-users/{id}/disable", target)
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .header("X-Aggregate-Version", "99"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("aggregate_version_stale"));
        assertThat(statusOf(target)).isEqualTo("active"); // untouched
    }

    @Test
    void disable_with_a_stale_mfa_assertion_is_401_and_leaves_the_target_unchanged() throws Exception {
        String bearer = login();
        UUID target = activeAdmin();
        // Age the session's login assertion past the SENSITIVE 5-minute window (B4 §6.4).
        jdbc.update("UPDATE auth_otp_challenge SET consumed_at = now() - interval '10 minutes' "
                + "WHERE assertion_id = (SELECT mfa_assertion_id FROM auth_session WHERE session_id = ?)",
                UUID.fromString(bearer));

        mvc.perform(post("/admin-users/{id}/disable", target)
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .header("X-Aggregate-Version", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error_code").value("mfa_assertion_expired"));
        assertThat(statusOf(target)).isEqualTo("active");
    }

    @Test
    void disable_happy_path_transitions_the_target_and_emits_the_envelope() throws Exception {
        String bearer = login();
        UUID target = activeAdmin();
        mvc.perform(post("/admin-users/{id}/disable", target)
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .header("X-Aggregate-Version", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emitted_events[0].event_type").value("admin_iam.AdminUser.Disabled"));
        assertThat(statusOf(target)).isEqualTo("disabled");
    }

    // --- error-taxonomy completeness (the edge never leaks a non-B4 body or a 500 on client input) ---

    @Test
    void a_missing_command_id_header_is_400_in_the_b4_shape_not_a_framework_problem_detail() throws Exception {
        String bearer = login();
        mvc.perform(post("/admin-users/provision")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", newEmail(), "display_name", "X", "phone", phone()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("missing_header"))
                .andExpect(jsonPath("$.error_category").value("bad_request"));
    }

    @Test
    void a_get_for_an_unknown_admin_is_404_in_the_b4_shape() throws Exception {
        String bearer = login();
        mvc.perform(get("/admin-users/{id}", UUID.randomUUID()).header("Authorization", "Bearer " + bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error_code").value("not_found"));
    }

    @Test
    void login_with_a_missing_field_is_400_not_a_500_on_the_open_route() throws Exception {
        mvc.perform(post("/auth/login/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", admin.email())))) // no password
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("validation_failed"));
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** Drives the two-step login over HTTP and returns the bearer (the session id). */
    private String login() throws Exception {
        MvcResult pw = mvc.perform(post("/auth/login/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("email", admin.email(), "password", admin.password()))))
                .andExpect(status().isOk()).andReturn();
        String challengeId = node(pw).get("challenge_id").asText();
        String code = notifier.lastCodeFor(admin.identityId()).orElseThrow();

        MvcResult otp = mvc.perform(post("/auth/login/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("challenge_id", challengeId, "code", code))))
                .andExpect(status().isOk()).andReturn();
        return node(otp).get("bearer").asText();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder provision(
            String bearer, UUID commandId, String email) throws Exception {
        return post("/admin-users/provision")
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", commandId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of(
                        "email", email, "display_name", "New Admin", "phone", phone())));
    }

    private UUID activeAdmin() {
        UUID identityId = auth.provisionIdentity("admin_user", newEmail(), phone(), "Target");
        UUID adminUserId = UUID.randomUUID();
        jdbc.update("INSERT INTO admin_user (admin_user_id, identity_id, email, display_name, status) "
                        + "VALUES (?, ?, ?, ?, 'active')",
                adminUserId, identityId, "t-" + adminUserId + "@arthvritt.test", "Target");
        return adminUserId;
    }

    private JsonNode node(MvcResult res) throws Exception {
        return json.readTree(res.getResponse().getContentAsString());
    }

    private long auditCount() {
        return jdbc.queryForObject("SELECT count(*) FROM sys_audit_event", Long.class);
    }

    private int envelopesFor(String eventType, UUID aggregateId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, eventType, aggregateId);
    }

    private int countCreatedFor(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM admin_user WHERE email = ?", Integer.class, email);
    }

    private String statusOf(UUID adminUserId) {
        return jdbc.queryForObject("SELECT status::text FROM admin_user WHERE admin_user_id = ?",
                String.class, adminUserId);
    }

    private static String newEmail() {
        return "user-" + UUID.randomUUID() + "@arthvritt.test";
    }

    private static String phone() {
        return "+9198" + (10000000 + new java.util.Random().nextInt(89999999));
    }

    private record SuperAdmin(UUID adminUserId, UUID identityId, String email, String password) {
    }
}
