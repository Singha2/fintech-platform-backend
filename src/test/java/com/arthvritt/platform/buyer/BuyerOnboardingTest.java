package com.arthvritt.platform.buyer;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WS-2 buyer onboarding (see docs/modules/WS-2-buyer-active.md §7): one buyer driven
 * {@code nominated → active} over HTTP, all admin-on-behalf, plus its OTP-only acknowledgment user.
 * Asserts the linear state machine + the BA.3 activation gate, per-command SoD, and idempotency.
 */
class BuyerOnboardingTest extends AbstractEdgeHttpTest {

    private static final Random RND = new Random();

    private String ops;
    private String credit;

    @BeforeEach
    void seedActors() {
        notifier.clear();
        ops = bearerFor(seedAdminWithRoles("ops_executive"));
        credit = bearerFor(seedAdminWithRoles("credit_reviewer"));
    }

    @Test
    void happy_path_drives_a_buyer_from_nominated_to_active() throws Exception {
        UUID buyer = nominateBuyer();
        assertThat(statusOf(buyer)).isEqualTo("nominated");

        send(post("/buyers/{id}/record-identity-verified", buyer), ops, buyer, Map.of());
        assertThat(statusOf(buyer)).isEqualTo("identity_verified");

        send(post("/buyers/{id}/record-credit-assessment", buyer), credit, buyer,
                Map.of("credit_limit_paise", 50_00_00_000L)); // ₹5 Cr < ₹10 Cr (no four-eyes)
        assertThat(statusOf(buyer)).isEqualTo("credit_assessed");

        send(post("/buyers/{id}/start-engagement", buyer), ops, buyer, Map.of());
        assertThat(statusOf(buyer)).isEqualTo("engagement_started");

        send(post("/buyers/{id}/designate-ack-user", buyer), ops, buyer, ackUserBody());
        send(post("/buyers/{id}/confirm-payment-instruction", buyer), ops, buyer, Map.of());

        send(post("/buyers/{id}/activate", buyer), ops, buyer, Map.of());
        assertThat(statusOf(buyer)).isEqualTo("active");
    }

    @Test
    void activate_without_an_ack_user_is_rejected() throws Exception { // BA.3 gate
        UUID buyer = nominateBuyer();
        send(post("/buyers/{id}/record-identity-verified", buyer), ops, buyer, Map.of());
        send(post("/buyers/{id}/record-credit-assessment", buyer), credit, buyer,
                Map.of("credit_limit_paise", 50_00_00_000L));
        send(post("/buyers/{id}/start-engagement", buyer), ops, buyer, Map.of());
        // No ack user, no payment rule → BA.3 must block activation.
        mvc.perform(withEnvelope(post("/buyers/{id}/activate", buyer), ops, buyer, Map.of()))
                .andExpect(status().is4xxClientError());
        assertThat(statusOf(buyer)).isEqualTo("engagement_started"); // unchanged
    }

    @Test
    void record_credit_assessment_by_a_non_credit_reviewer_is_403() throws Exception { // SoD
        UUID buyer = nominateBuyer();
        mvc.perform(withEnvelope(post("/buyers/{id}/record-credit-assessment", buyer), ops, buyer,
                        Map.of("credit_limit_paise", 50_00_00_000L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));
    }

    @Test
    void designate_ack_user_provisions_an_otp_only_identity() throws Exception { // AU.1/DL-021
        UUID buyer = nominateBuyer();
        send(post("/buyers/{id}/record-identity-verified", buyer), ops, buyer, Map.of());
        send(post("/buyers/{id}/record-credit-assessment", buyer), credit, buyer,
                Map.of("credit_limit_paise", 50_00_00_000L));
        send(post("/buyers/{id}/start-engagement", buyer), ops, buyer, Map.of());
        send(post("/buyers/{id}/designate-ack-user", buyer), ops, buyer, ackUserBody());

        UUID identityId = jdbc.queryForObject(
                "SELECT identity_id FROM buyer_ack_user WHERE buyer_id = ? AND is_active = TRUE", UUID.class, buyer);
        assertThat(jdbc.queryForObject("SELECT kind::text FROM auth_identity WHERE identity_id = ?",
                String.class, identityId)).isEqualTo("acknowledgment_user");
        // OTP-only: no password credential, no MFA factor.
        assertThat(credentialCount(identityId)).isZero();
        assertThat(mfaFactorCount(identityId)).isZero();
    }

    @Test
    void replaying_nominate_yields_a_single_buyer() throws Exception {
        UUID commandId = UUID.randomUUID();
        Map<String, Object> body = nominateBody();
        MvcResult first = mvc.perform(nominateRequest(commandId, body)).andExpect(status().isCreated()).andReturn();
        UUID buyer = UUID.fromString(node(first).get("aggregate_id").asText());

        mvc.perform(nominateRequest(commandId, body)).andExpect(status().isOk()); // replay → 200
        Integer count = jdbc.queryForObject("SELECT count(*) FROM buyer_account WHERE buyer_id = ?",
                Integer.class, buyer);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void get_buyer_returns_the_aggregate_read() throws Exception {
        UUID buyer = nominateBuyer();
        mvc.perform(get("/buyers/{id}", buyer).header("Authorization", "Bearer " + ops))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyer_id").value(buyer.toString()))
                .andExpect(jsonPath("$.status").value("nominated"));
    }

    // --- review-driven hardening -------------------------------------------------------------------

    @Test
    void a_non_positive_credit_limit_is_rejected_not_a_db_500() throws Exception { // positive_money_paise
        UUID buyer = nominateBuyer();
        send(post("/buyers/{id}/record-identity-verified", buyer), ops, buyer, Map.of());
        mvc.perform(withEnvelope(post("/buyers/{id}/record-credit-assessment", buyer), credit, buyer,
                        Map.of("credit_limit_paise", 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("validation_failed"));
    }

    @Test
    void designating_an_ack_user_with_a_duplicate_email_is_a_clean_400() throws Exception {
        UUID buyer = nominateBuyer();
        send(post("/buyers/{id}/record-identity-verified", buyer), ops, buyer, Map.of());
        send(post("/buyers/{id}/record-credit-assessment", buyer), credit, buyer,
                Map.of("credit_limit_paise", 50_00_00_000L));
        send(post("/buyers/{id}/start-engagement", buyer), ops, buyer, Map.of());

        Map<String, Object> ack = ackUserBody();
        send(post("/buyers/{id}/designate-ack-user", buyer), ops, buyer, ack);
        // A second designation reusing the same email hits the auth_identity UNIQUE → clean 400, not a 500.
        mvc.perform(withEnvelope(post("/buyers/{id}/designate-ack-user", buyer), ops, buyer, ack))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("validation_failed"));
    }

    // --- helpers -----------------------------------------------------------------------------------

    private UUID nominateBuyer() throws Exception {
        MvcResult res = mvc.perform(nominateRequest(UUID.randomUUID(), nominateBody()))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(node(res).get("aggregate_id").asText());
    }

    private MockHttpServletRequestBuilder nominateRequest(UUID commandId, Map<String, Object> body) throws Exception {
        return post("/buyers/nominate")
                .header("Authorization", "Bearer " + credit) // nominate is a credit_reviewer action
                .header("X-Command-Id", commandId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
    }

    private Map<String, Object> nominateBody() {
        // gstin + mca_cin are UNIQUE on buyer_account — every buyer needs its own.
        String pan = letters(5) + String.format("%04d", RND.nextInt(10000)) + letters(1);
        return Map.of("legal_name", "Buyer " + UUID.randomUUID(),
                "mca_cin", "U72200KA2020PTC" + String.format("%06d", RND.nextInt(1_000_000)),
                "gstin", "27" + pan + "1Z5", "sector", "manufacturing");
    }

    private Map<String, Object> ackUserBody() {
        return Map.of("email", "ack-" + UUID.randomUUID() + "@buyer.test", "phone", phone(),
                "display_name", "Ack User");
    }

    private void send(MockHttpServletRequestBuilder builder, String bearer, UUID buyer, Map<String, Object> body)
            throws Exception {
        mvc.perform(withEnvelope(builder, bearer, buyer, body)).andExpect(status().is2xxSuccessful());
    }

    private MockHttpServletRequestBuilder withEnvelope(MockHttpServletRequestBuilder builder, String bearer,
                                                       UUID buyer, Map<String, Object> body) throws Exception {
        return builder
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", UUID.randomUUID().toString())
                .header("X-Aggregate-Version", String.valueOf(versionOf(buyer)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
    }

    private String statusOf(UUID buyer) {
        return jdbc.queryForObject("SELECT status::text FROM buyer_account WHERE buyer_id = ?", String.class, buyer);
    }

    private int versionOf(UUID buyer) {
        return jdbc.queryForObject("SELECT aggregate_version FROM buyer_account WHERE buyer_id = ?",
                Integer.class, buyer);
    }

    private int credentialCount(UUID identityId) {
        return jdbc.queryForObject("SELECT count(*) FROM auth_credential WHERE identity_id = ?", Integer.class, identityId);
    }

    private int mfaFactorCount(UUID identityId) {
        return jdbc.queryForObject("SELECT count(*) FROM auth_mfa_factor WHERE identity_id = ?", Integer.class, identityId);
    }

    private static String letters(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append((char) ('A' + RND.nextInt(26)));
        }
        return sb.toString();
    }
}
