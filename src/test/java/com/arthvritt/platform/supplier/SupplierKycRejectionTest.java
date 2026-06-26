package com.arthvritt.platform.supplier;

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
 * M7-B (DL-BE-057) — the supplier KYC-rejected branch. Compliance can reject a submitted KYC file
 * (maker ≠ checker + MFA, reusing the generic ComplianceService); the supplier holds at {@code kyc_submitted}
 * and cannot reach {@code kyc_approved} until a fresh resubmit → approve cycle. Mirrors M10-C (gated one
 * stage earlier, at kyc_submitted).
 */
class SupplierKycRejectionTest extends AbstractEdgeHttpTest {

    private static final Random RND = new Random();

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
        UUID supplier = kycSubmitted(ops, ops);

        send(post("/suppliers/{id}/record-kyc-rejected", supplier), compliance, supplier,
                Map.of("reason", "Document mismatch"));
        assertThat(kycFileStatus(supplier)).isEqualTo("rejected");
        assertThat(statusOf(supplier)).isEqualTo("kyc_submitted");

        mvc.perform(withEnvelope(post("/suppliers/{id}/record-kyc-approved", supplier), compliance, supplier, Map.of()))
                .andExpect(status().is4xxClientError());
        assertThat(statusOf(supplier)).isEqualTo("kyc_submitted");

        send(post("/suppliers/{id}/resubmit-kyc", supplier), ops, supplier, Map.of());
        assertThat(kycFileStatus(supplier)).isEqualTo("submitted");
        send(post("/suppliers/{id}/record-kyc-approved", supplier), compliance, supplier, Map.of());
        assertThat(statusOf(supplier)).isEqualTo("kyc_approved");
    }

    @Test
    void record_kyc_rejected_by_a_non_compliance_actor_is_403() throws Exception { // SoD
        UUID supplier = kycSubmitted(ops, ops);
        mvc.perform(withEnvelope(post("/suppliers/{id}/record-kyc-rejected", supplier), ops, supplier,
                        Map.of("reason", "x")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));
        assertThat(kycFileStatus(supplier)).isEqualTo("submitted");
    }

    @Test
    void the_kyc_submitter_cannot_reject_their_own_file() throws Exception { // maker ≠ checker (KF.2/C4)
        String dual = bearerFor(seedAdminWithRoles("ops_executive", "compliance_reviewer"));
        UUID supplier = kycSubmitted(dual, dual); // submitted_by = dual
        mvc.perform(withEnvelope(post("/suppliers/{id}/record-kyc-rejected", supplier), dual, supplier,
                        Map.of("reason", "self-reject")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("checker_equals_maker"));
        assertThat(kycFileStatus(supplier)).isEqualTo("submitted");
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** Drives a fresh supplier to 'kyc_submitted'; {@code submitter} does identity-verify + submit-kyc. */
    private UUID kycSubmitted(String creator, String submitter) throws Exception {
        UUID supplier = createSupplier(creator);
        send(post("/suppliers/{id}/record-identity-verified", supplier), submitter, supplier, Map.of());
        send(post("/suppliers/{id}/submit-kyc", supplier), submitter, supplier, Map.of());
        return supplier;
    }

    private UUID createSupplier(String bearer) throws Exception {
        String pan = letters(5) + String.format("%04d", RND.nextInt(10000)) + letters(1);
        Map<String, Object> body = Map.of(
                "legal_name", "Supplier " + UUID.randomUUID(), "constitution_type", "private_limited",
                "pan", pan, "gstin", "27" + pan + "1Z5",
                "cin", "U72200KA2020PTC" + String.format("%06d", RND.nextInt(1_000_000)));
        MvcResult res = mvc.perform(post("/suppliers/create")
                        .header("Authorization", "Bearer " + bearer)
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

    private String kycFileStatus(UUID supplier) {
        return jdbc.queryForObject(
                "SELECT status::text FROM comp_kyc_file WHERE subject_id = ? AND subject_type = 'supplier'",
                String.class, supplier);
    }

    private int versionOf(UUID supplier) {
        return jdbc.queryForObject("SELECT aggregate_version FROM sup_account WHERE supplier_id = ?",
                Integer.class, supplier);
    }

    private static String letters(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append((char) ('A' + RND.nextInt(26)));
        }
        return sb.toString();
    }
}
