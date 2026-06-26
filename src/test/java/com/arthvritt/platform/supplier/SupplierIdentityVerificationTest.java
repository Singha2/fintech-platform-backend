package com.arthvritt.platform.supplier;

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
 * M7-A (DL-BE-056) — supplier identity (PAN + GSTIN, + CIN via MCA21) is verified through the BC17 ACL,
 * not self-attested (SA8.3/C24). Admin-triggered (Ops uploads the offline-collected ids; the aggregator
 * decides). Mirrors M10-A: a VALID result advances to {@code identity_verified} + issues a real
 * {@code gate_verification}; a forced-INVALID PAN blocks the transition with 422 {@code verification_failed}.
 */
class SupplierIdentityVerificationTest extends AbstractEdgeHttpTest {

    private static final Random RND = new Random();

    private String ops;

    @BeforeEach
    void seedActors() {
        notifier.clear();
        ops = bearerFor(seedAdminWithRoles("ops_executive"));
    }

    @Test
    void valid_identity_advances_and_issues_bc17_verifications() throws Exception {
        UUID supplier = createSupplier(validPan());
        send(post("/suppliers/{id}/record-identity-verified", supplier), ops, supplier, Map.of());

        assertThat(statusOf(supplier)).isEqualTo("identity_verified");
        Integer pans = jdbc.queryForObject(
                "SELECT count(*) FROM gate_verification WHERE subject_id = ? AND api_name = 'verify_pan'",
                Integer.class, supplier);
        Integer gstins = jdbc.queryForObject(
                "SELECT count(*) FROM gate_verification WHERE subject_id = ? AND api_name = 'verify_gstin'",
                Integer.class, supplier);
        assertThat(pans).isGreaterThanOrEqualTo(1);
        assertThat(gstins).isGreaterThanOrEqualTo(1);
    }

    @Test
    void an_invalid_pan_blocks_identity_with_422() throws Exception {
        UUID supplier = createSupplier(StubVerificationVendorClient.FAIL_PAN); // ZZZZZ9999Z (valid format)
        mvc.perform(withEnvelope(post("/suppliers/{id}/record-identity-verified", supplier), ops, supplier, Map.of()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error_code").value("verification_failed"));
        assertThat(statusOf(supplier)).isEqualTo("created");
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static String validPan() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append((char) ('A' + RND.nextInt(26)));
        }
        return sb.append(String.format("%04d", RND.nextInt(10000))).append((char) ('A' + RND.nextInt(26))).toString();
    }

    private UUID createSupplier(String pan) throws Exception {
        Map<String, Object> body = Map.of(
                "legal_name", "Supplier " + UUID.randomUUID(), "constitution_type", "private_limited",
                "pan", pan, "gstin", "27" + pan + "1Z5",
                "cin", "U72200KA2020PTC" + String.format("%06d", RND.nextInt(1_000_000)));
        MvcResult res = mvc.perform(post("/suppliers/create")
                        .header("Authorization", "Bearer " + ops)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(node(res).get("aggregate_id").asText());
    }

    private void send(MockHttpServletRequestBuilder builder, String bearer, UUID supplier, Map<String, Object> body)
            throws Exception {
        mvc.perform(withEnvelope(builder, bearer, supplier, body)).andExpect(status().is2xxSuccessful());
    }

    private MockHttpServletRequestBuilder withEnvelope(MockHttpServletRequestBuilder builder, String bearer,
                                                       UUID supplier, Map<String, Object> body) throws Exception {
        return builder
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", UUID.randomUUID().toString())
                .header("X-Aggregate-Version", String.valueOf(versionOf(supplier)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
    }

    private String statusOf(UUID supplier) {
        return jdbc.queryForObject("SELECT status::text FROM sup_account WHERE supplier_id = ?", String.class, supplier);
    }

    private int versionOf(UUID supplier) {
        return jdbc.queryForObject("SELECT aggregate_version FROM sup_account WHERE supplier_id = ?",
                Integer.class, supplier);
    }
}
