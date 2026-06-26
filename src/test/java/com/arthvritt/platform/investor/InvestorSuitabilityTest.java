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
 * M10-B (DL-BE-046) — the suitability mismatch + override-ack path (IA.4/C21/G26) and the IA.3 activation
 * gate. WS-3 hard-coded {@code mismatch=false}; here a mismatched investor cannot be activated until a
 * Compliance reviewer acknowledges the risk override (stamps {@code override_text_hash}).
 */
class InvestorSuitabilityTest extends AbstractEdgeHttpTest {

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
    void a_mismatch_blocks_activation_until_the_override_is_acknowledged() throws Exception {
        UUID investor = miaSigned(true);

        mvc.perform(withEnvelope(post("/investors/{id}/activate", investor), ops, investor, Map.of()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error_code").value("suitability_override_required"));
        assertThat(statusOf(investor)).isEqualTo("mia_signed");

        send(post("/investors/{id}/acknowledge-suitability-override", investor), compliance, investor,
                Map.of("override_text", "I understand and accept the risk mismatch."));
        send(post("/investors/{id}/activate", investor), ops, investor, Map.of());
        assertThat(statusOf(investor)).isEqualTo("active");
    }

    @Test
    void no_mismatch_activates_without_an_override() throws Exception {
        UUID investor = miaSigned(false);
        send(post("/investors/{id}/activate", investor), ops, investor, Map.of());
        assertThat(statusOf(investor)).isEqualTo("active");
    }

    @Test
    void acknowledging_an_override_on_a_non_mismatch_assessment_is_rejected() throws Exception {
        UUID investor = miaSigned(false);
        mvc.perform(withEnvelope(post("/investors/{id}/acknowledge-suitability-override", investor), compliance,
                        investor, Map.of("override_text", "not needed")))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void acknowledging_an_override_by_a_non_compliance_actor_is_403() throws Exception { // SoD
        UUID investor = miaSigned(true);
        mvc.perform(withEnvelope(post("/investors/{id}/acknowledge-suitability-override", investor), ops, investor,
                        Map.of("override_text", "I accept")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** Drives a fresh investor to 'mia_signed' with the given suitability mismatch flag. */
    private UUID miaSigned(boolean mismatch) throws Exception {
        UUID investor = signedUp();
        send(post("/investors/{id}/record-identity-verified", investor), ops, investor,
                Map.of("pan", PAN, "aadhaar_last4", AADHAAR4));
        send(post("/investors/{id}/submit-kyc", investor), ops, investor, Map.of());
        send(post("/investors/{id}/assess-suitability", investor), compliance, investor, Map.of("mismatch", mismatch));
        send(post("/investors/{id}/complete-financial-profile", investor), ops, investor,
                Map.of("bank_account_last4", BANK4));
        send(post("/investors/{id}/record-kyc-approved", investor), compliance, investor, Map.of());
        send(post("/investors/{id}/record-mia-signed", investor), ops, investor, Map.of());
        assertThat(statusOf(investor)).isEqualTo("mia_signed");
        return investor;
    }

    private UUID signedUp() throws Exception {
        String email = "inv-" + UUID.randomUUID() + "@arthvritt.test";
        String phone = "+919800000000";
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
