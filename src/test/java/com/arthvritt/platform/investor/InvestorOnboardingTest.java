package com.arthvritt.platform.investor;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WS-3 investor onboarding (see docs/modules/WS-3-investor-active.md §7): one investor driven
 * {@code signed_up → active} over HTTP, invite-gated, all admin-on-behalf. Asserts the linear state
 * machine + the IA.3 activation gate, the C20 invite-gate, KYC maker-checker, and idempotency.
 */
class InvestorOnboardingTest extends AbstractEdgeHttpTest {

    private static final String PAN = "ABCDE1234F";
    private static final String AADHAAR4 = "1234";
    private static final String BANK4 = "5678";

    private String ops;
    private String compliance;

    @BeforeEach
    void seedActors() {
        notifier.clear();
        ops = bearerFor(seedAdminWithRoles("ops_executive"));
        compliance = bearerFor(seedAdminWithRoles("compliance_reviewer"));
    }

    @Test
    void happy_path_drives_an_investor_from_signed_up_to_active() throws Exception {
        String email = email();
        String phone = phone();
        UUID invite = issueInvite(email, phone);
        UUID investor = signup(invite, email, phone);
        assertThat(statusOf(investor)).isEqualTo("signed_up");

        send(post("/investors/{id}/record-identity-verified", investor), ops, investor,
                Map.of("pan", PAN, "aadhaar_last4", AADHAAR4));
        assertThat(statusOf(investor)).isEqualTo("identity_verified");

        send(post("/investors/{id}/submit-kyc", investor), ops, investor, Map.of());
        send(post("/investors/{id}/assess-suitability", investor), compliance, investor, Map.of());
        assertThat(statusOf(investor)).isEqualTo("suitability_assessed");

        send(post("/investors/{id}/complete-financial-profile", investor), ops, investor,
                Map.of("bank_account_last4", BANK4));
        send(post("/investors/{id}/record-kyc-approved", investor), compliance, investor, Map.of());
        assertThat(statusOf(investor)).isEqualTo("kyc_approved");

        send(post("/investors/{id}/record-mia-signed", investor), ops, investor, Map.of());
        send(post("/investors/{id}/activate", investor), ops, investor, Map.of());

        assertThat(statusOf(investor)).isEqualTo("active");
        assertThat(jdbc.queryForObject("SELECT kyc_refresh_due_at IS NOT NULL FROM inv_account WHERE investor_id = ?",
                Boolean.class, investor)).isTrue();
    }

    @Test
    void signup_with_a_mismatched_email_is_rejected() throws Exception { // C20 invite-gate
        String email = email();
        UUID invite = issueInvite(email, phone());
        mvc.perform(signupRequest(UUID.randomUUID(), invite, "someone-else@arthvritt.test", phone()))
                .andExpect(status().is4xxClientError());
        Integer accounts = jdbc.queryForObject("SELECT count(*) FROM inv_account WHERE invite_id = ?",
                Integer.class, invite);
        assertThat(accounts).isZero();
    }

    @Test
    void assess_suitability_by_a_non_compliance_reviewer_is_403() throws Exception { // SoD
        String email = email();
        String phone = phone();
        UUID investor = signup(issueInvite(email, phone), email, phone);
        send(post("/investors/{id}/record-identity-verified", investor), ops, investor,
                Map.of("pan", PAN, "aadhaar_last4", AADHAAR4));
        send(post("/investors/{id}/submit-kyc", investor), ops, investor, Map.of());
        mvc.perform(withEnvelope(post("/investors/{id}/assess-suitability", investor), ops, investor, Map.of()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));
    }

    @Test
    void kyc_approval_by_the_submitter_is_a_clean_409() throws Exception { // maker ≠ checker (KF.2/C4)
        String dual = bearerFor(seedAdminWithRoles("ops_executive", "compliance_reviewer"));
        String email = email();
        String phone = phone();
        UUID investor = signup(issueInvite(email, phone, dual), email, phone, dual);
        send(post("/investors/{id}/record-identity-verified", investor), dual, investor,
                Map.of("pan", PAN, "aadhaar_last4", AADHAAR4));
        send(post("/investors/{id}/submit-kyc", investor), dual, investor, Map.of()); // submitted_by = dual
        send(post("/investors/{id}/assess-suitability", investor), dual, investor, Map.of());
        send(post("/investors/{id}/complete-financial-profile", investor), dual, investor,
                Map.of("bank_account_last4", BANK4));

        mvc.perform(withEnvelope(post("/investors/{id}/record-kyc-approved", investor), dual, investor, Map.of()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("checker_equals_maker"));
        assertThat(statusOf(investor)).isEqualTo("financial_profile_completed"); // not approved
    }

    @Test
    void replaying_signup_yields_a_single_investor() throws Exception {
        String email = email();
        String phone = phone();
        UUID invite = issueInvite(email, phone);
        UUID commandId = UUID.randomUUID();
        MvcResult first = mvc.perform(signupRequest(commandId, invite, email, phone))
                .andExpect(status().isCreated()).andReturn();
        UUID investor = UUID.fromString(node(first).get("aggregate_id").asText());

        mvc.perform(signupRequest(commandId, invite, email, phone)).andExpect(status().isOk()); // replay → 200
        Integer count = jdbc.queryForObject("SELECT count(*) FROM inv_account WHERE investor_id = ?",
                Integer.class, investor);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void get_investor_returns_the_aggregate_read() throws Exception {
        String email = email();
        String phone = phone();
        UUID investor = signup(issueInvite(email, phone), email, phone);
        mvc.perform(get("/investors/{id}", investor).header("Authorization", "Bearer " + ops))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.investor_id").value(investor.toString()))
                .andExpect(jsonPath("$.status").value("signed_up"));
    }

    @Test
    void a_malformed_bank_account_last4_is_rejected_not_a_db_500() throws Exception {
        String email = email();
        String phone = phone();
        UUID investor = signup(issueInvite(email, phone), email, phone);
        // The edge format-check fires before dispatch, so the investor's state is irrelevant.
        mvc.perform(withEnvelope(post("/investors/{id}/complete-financial-profile", investor), ops, investor,
                        Map.of("bank_account_last4", "12345"))) // 5 chars → CHAR(4) would 500
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("validation_failed"));
    }

    @Test
    void a_malformed_pan_is_rejected_not_a_db_500() throws Exception {
        String email = email();
        String phone = phone();
        UUID investor = signup(issueInvite(email, phone), email, phone);
        mvc.perform(withEnvelope(post("/investors/{id}/record-identity-verified", investor), ops, investor,
                        Map.of("pan", "NOTAPAN", "aadhaar_last4", AADHAAR4)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("validation_failed"));
    }

    // --- helpers -----------------------------------------------------------------------------------

    private UUID issueInvite(String email, String phone) throws Exception {
        return issueInvite(email, phone, compliance);
    }

    private UUID issueInvite(String email, String phone, String complianceBearer) throws Exception {
        MvcResult res = mvc.perform(post("/investor-invites/issue")
                        .header("Authorization", "Bearer " + complianceBearer)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "phone", phone))))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(node(res).get("aggregate_id").asText());
    }

    private UUID signup(UUID invite, String email, String phone) throws Exception {
        return signup(invite, email, phone, ops);
    }

    private UUID signup(UUID invite, String email, String phone, String opsBearer) throws Exception {
        MvcResult res = mvc.perform(signupRequest(UUID.randomUUID(), invite, email, phone, opsBearer))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(node(res).get("aggregate_id").asText());
    }

    private MockHttpServletRequestBuilder signupRequest(UUID commandId, UUID invite, String email, String phone)
            throws Exception {
        return signupRequest(commandId, invite, email, phone, ops);
    }

    private MockHttpServletRequestBuilder signupRequest(UUID commandId, UUID invite, String email, String phone,
                                                        String opsBearer) throws Exception {
        return post("/investors/sign-up")
                .header("Authorization", "Bearer " + opsBearer)
                .header("X-Command-Id", commandId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("invite_id", invite.toString(), "email", email,
                        "phone", phone, "sub_type", "resident_individual")));
    }

    private void send(MockHttpServletRequestBuilder builder, String bearer, UUID investor, Map<String, Object> body)
            throws Exception {
        mvc.perform(withEnvelope(builder, bearer, investor, body)).andExpect(status().is2xxSuccessful());
    }

    private MockHttpServletRequestBuilder withEnvelope(MockHttpServletRequestBuilder builder, String bearer,
                                                       UUID investor, Map<String, Object> body) throws Exception {
        return builder
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", UUID.randomUUID().toString())
                .header("X-Aggregate-Version", String.valueOf(versionOf(investor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
    }

    private String statusOf(UUID investor) {
        return jdbc.queryForObject("SELECT status::text FROM inv_account WHERE investor_id = ?", String.class, investor);
    }

    private int versionOf(UUID investor) {
        return jdbc.queryForObject("SELECT aggregate_version FROM inv_account WHERE investor_id = ?",
                Integer.class, investor);
    }

    private static String email() {
        return "inv-" + UUID.randomUUID() + "@arthvritt.test";
    }
}
