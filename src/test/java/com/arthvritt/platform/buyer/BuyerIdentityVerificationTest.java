package com.arthvritt.platform.buyer;

import com.arthvritt.platform.verification.StubVerificationVendorClient;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M8-A (DL-BE-058) — buyer identity (GSTIN + CIN via MCA21) is verified through the BC17 ACL, not
 * self-attested (BA.4/C24). Admin-triggered. Mirrors M7-A (no PAN, no KYC): a VALID result advances to
 * {@code identity_verified} + issues a real {@code gate_verification}; a forced-INVALID GSTIN blocks the
 * transition with 422 {@code verification_failed}.
 */
class BuyerIdentityVerificationTest extends AbstractEdgeHttpTest {

    private static final Random RND = new Random();

    private String ops;
    private String credit;

    @BeforeEach
    void seedActors() {
        notifier.clear();
        ops = bearerFor(seedAdminWithRoles("ops_executive"));
        credit = bearerFor(seedAdminWithRoles("credit_reviewer")); // nominate is a Credit Reviewer command
    }

    @Test
    void valid_identity_advances_and_issues_bc17_verifications() throws Exception {
        UUID buyer = nominate(validGstin());
        send(post("/buyers/{id}/record-identity-verified", buyer), ops, buyer, Map.of());

        assertThat(statusOf(buyer)).isEqualTo("identity_verified");
        Integer gstins = jdbc.queryForObject(
                "SELECT count(*) FROM gate_verification WHERE subject_id = ? AND api_name = 'verify_gstin'",
                Integer.class, buyer);
        Integer mca = jdbc.queryForObject(
                "SELECT count(*) FROM gate_verification WHERE subject_id = ? AND api_name = 'fetch_mca21'",
                Integer.class, buyer);
        assertThat(gstins).isGreaterThanOrEqualTo(1);
        assertThat(mca).isGreaterThanOrEqualTo(1);
    }

    @Test
    void an_invalid_gstin_blocks_identity_with_422() throws Exception {
        UUID buyer = nominate(StubVerificationVendorClient.FAIL_GSTIN);
        mvc.perform(withEnvelope(post("/buyers/{id}/record-identity-verified", buyer), ops, buyer, Map.of()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error_code").value("verification_failed"));
        assertThat(statusOf(buyer)).isEqualTo("nominated");
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static String validGstin() {
        StringBuilder pan = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            pan.append((char) ('A' + RND.nextInt(26)));
        }
        pan.append(String.format("%04d", RND.nextInt(10000))).append((char) ('A' + RND.nextInt(26)));
        return "27" + pan + "1Z5";
    }

    private UUID nominate(String gstin) throws Exception {
        Map<String, Object> body = Map.of(
                "legal_name", "Buyer " + UUID.randomUUID(),
                "mca_cin", "U72200KA2020PTC" + String.format("%06d", RND.nextInt(1_000_000)),
                "gstin", gstin, "sector", "manufacturing");
        MvcResult res = mvc.perform(post("/buyers/nominate")
                        .header("Authorization", "Bearer " + credit)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(node(res).get("aggregate_id").asText());
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
}
