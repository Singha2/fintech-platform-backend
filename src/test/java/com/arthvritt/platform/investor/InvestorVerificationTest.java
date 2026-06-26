package com.arthvritt.platform.investor;

import com.arthvritt.platform.verification.StubVerificationVendorClient;
import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.BeforeEach;
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
 * M10-A (DL-BE-045) — identity (PAN) and bank (penny-drop) are verified through the BC17 ACL, not
 * self-attested (C24/IA.8). Admin-triggered: Ops uploads the offline-collected detail, the aggregator
 * decides. Proves: a VALID result advances + issues a real {@code gate_verification}; a forced INVALID
 * result blocks the transition with 422 {@code verification_failed}; the edge format-check still fires first.
 */
class InvestorVerificationTest extends AbstractEdgeHttpTest {

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
    void a_valid_pan_advances_identity_and_issues_a_bc17_verification() throws Exception {
        UUID investor = signedUp();
        send(post("/investors/{id}/record-identity-verified", investor), ops, investor,
                Map.of("pan", PAN, "aadhaar_last4", AADHAAR4));

        assertThat(statusOf(investor)).isEqualTo("identity_verified");
        Integer pans = jdbc.queryForObject(
                "SELECT count(*) FROM gate_verification WHERE subject_id = ? AND api_name = 'verify_pan'",
                Integer.class, investor);
        assertThat(pans).isGreaterThanOrEqualTo(1);
    }

    @Test
    void an_invalid_pan_blocks_identity_with_422() throws Exception {
        UUID investor = signedUp();
        mvc.perform(withEnvelope(post("/investors/{id}/record-identity-verified", investor), ops, investor,
                        Map.of("pan", StubVerificationVendorClient.FAIL_PAN, "aadhaar_last4", AADHAAR4)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error_code").value("verification_failed"));
        assertThat(statusOf(investor)).isEqualTo("signed_up");
    }

    @Test
    void a_failed_penny_drop_blocks_the_financial_profile_with_422() throws Exception {
        UUID investor = suitabilityAssessed();
        mvc.perform(withEnvelope(post("/investors/{id}/complete-financial-profile", investor), ops, investor,
                        Map.of("bank_account_last4", StubVerificationVendorClient.FAIL_BANK_LAST4)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error_code").value("verification_failed"));
        assertThat(statusOf(investor)).isEqualTo("suitability_assessed");
    }

    @Test
    void a_valid_penny_drop_completes_the_financial_profile() throws Exception {
        UUID investor = suitabilityAssessed();
        send(post("/investors/{id}/complete-financial-profile", investor), ops, investor,
                Map.of("bank_account_last4", BANK4));
        assertThat(statusOf(investor)).isEqualTo("financial_profile_completed");
    }

    @Test
    void a_malformed_pan_is_rejected_at_the_edge_before_any_verification() throws Exception {
        UUID investor = signedUp();
        mvc.perform(withEnvelope(post("/investors/{id}/record-identity-verified", investor), ops, investor,
                        Map.of("pan", "NOTAPAN", "aadhaar_last4", AADHAAR4)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("validation_failed"));
        assertThat(statusOf(investor)).isEqualTo("signed_up");
    }

    // --- helpers -----------------------------------------------------------------------------------

    private UUID signedUp() throws Exception {
        String email = "inv-" + UUID.randomUUID() + "@arthvritt.test";
        String phone = "+919800000000"; // phone is hashed for the invite-bind; no uniqueness needed
        UUID invite = issueInvite(email, phone);
        MvcResult res = mvc.perform(post("/investors/sign-up")
                        .header("Authorization", "Bearer " + ops)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("invite_id", invite.toString(), "email", email,
                                "phone", phone, "sub_type", "resident_individual"))))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(node(res).get("aggregate_id").asText());
    }

    private UUID suitabilityAssessed() throws Exception {
        UUID investor = signedUp();
        send(post("/investors/{id}/record-identity-verified", investor), ops, investor,
                Map.of("pan", PAN, "aadhaar_last4", AADHAAR4));
        send(post("/investors/{id}/submit-kyc", investor), ops, investor, Map.of());
        send(post("/investors/{id}/assess-suitability", investor), compliance, investor, Map.of());
        return investor;
    }

    private UUID issueInvite(String email, String phone) throws Exception {
        MvcResult res = mvc.perform(post("/investor-invites/issue")
                        .header("Authorization", "Bearer " + compliance)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "phone", phone))))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(node(res).get("aggregate_id").asText());
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
}
