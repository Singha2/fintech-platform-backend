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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M10-C (DL-BE-047) — the KYC-rejected branch. Compliance can reject a submitted KYC file (maker ≠ checker
 * + MFA); the investor holds at {@code financial_profile_completed} and cannot reach {@code kyc_approved}
 * until a fresh resubmit → approve cycle. The {@code inv_account_status} enum has no {@code kyc_rejected}
 * state, so the rejection lives on the {@code comp_kyc_file}, not the account.
 */
class InvestorKycRejectionTest extends AbstractEdgeHttpTest {

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
    void a_rejected_kyc_blocks_approval_until_resubmitted() throws Exception {
        UUID investor = financialProfileCompleted(ops, compliance);

        send(post("/investors/{id}/record-kyc-rejected", investor), compliance, investor,
                Map.of("reason", "Document mismatch"));
        assertThat(kycFileStatus(investor)).isEqualTo("rejected");
        assertThat(statusOf(investor)).isEqualTo("financial_profile_completed");

        // Approval is blocked while the file is rejected.
        mvc.perform(withEnvelope(post("/investors/{id}/record-kyc-approved", investor), compliance, investor, Map.of()))
                .andExpect(status().is4xxClientError());
        assertThat(statusOf(investor)).isEqualTo("financial_profile_completed");

        // Resubmit re-opens the file; approval then advances the account.
        send(post("/investors/{id}/resubmit-kyc", investor), ops, investor, Map.of());
        assertThat(kycFileStatus(investor)).isEqualTo("submitted");
        send(post("/investors/{id}/record-kyc-approved", investor), compliance, investor, Map.of());
        assertThat(statusOf(investor)).isEqualTo("kyc_approved");
    }

    @Test
    void record_kyc_rejected_by_a_non_compliance_actor_is_403() throws Exception { // SoD
        UUID investor = financialProfileCompleted(ops, compliance);
        mvc.perform(withEnvelope(post("/investors/{id}/record-kyc-rejected", investor), ops, investor,
                        Map.of("reason", "x")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));
        assertThat(kycFileStatus(investor)).isEqualTo("submitted");
    }

    @Test
    void the_kyc_submitter_cannot_reject_their_own_file() throws Exception { // maker ≠ checker (KF.2/C4)
        String dual = bearerFor(seedAdminWithRoles("ops_executive", "compliance_reviewer"));
        UUID investor = financialProfileCompleted(dual, dual); // submitted_by = dual
        mvc.perform(withEnvelope(post("/investors/{id}/record-kyc-rejected", investor), dual, investor,
                        Map.of("reason", "self-reject")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("checker_equals_maker"));
        assertThat(kycFileStatus(investor)).isEqualTo("submitted");
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** Drives a fresh investor to 'financial_profile_completed'; {@code submitter} does submit-kyc. */
    private UUID financialProfileCompleted(String submitter, String complianceBearer) throws Exception {
        UUID investor = signedUp(submitter);
        send(post("/investors/{id}/record-identity-verified", investor), submitter, investor,
                Map.of("pan", PAN, "aadhaar_last4", AADHAAR4));
        send(post("/investors/{id}/submit-kyc", investor), submitter, investor, Map.of());
        send(post("/investors/{id}/assess-suitability", investor), complianceBearer, investor, Map.of("mismatch", false));
        send(post("/investors/{id}/complete-financial-profile", investor), submitter, investor,
                Map.of("bank_account_last4", BANK4));
        return investor;
    }

    private UUID signedUp(String opsBearer) throws Exception {
        String email = "inv-" + UUID.randomUUID() + "@arthvritt.test";
        String phone = "+919800000000";
        UUID invite = issueInvite(email, phone);
        MvcResult res = mvc.perform(post("/investors/sign-up")
                        .header("Authorization", "Bearer " + opsBearer)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("invite_id", invite.toString(), "email", email,
                                "phone", phone, "sub_type", "resident_individual"))))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(node(res).get("aggregate_id").asText());
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

    private String kycFileStatus(UUID investor) {
        return jdbc.queryForObject(
                "SELECT status::text FROM comp_kyc_file WHERE subject_id = ? AND subject_type = 'investor'",
                String.class, investor);
    }

    private int versionOf(UUID investor) {
        return jdbc.queryForObject("SELECT aggregate_version FROM inv_account WHERE investor_id = ?",
                Integer.class, investor);
    }
}
