package com.arthvritt.platform.supplier;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WS-1 supplier onboarding (see docs/modules/WS-1-supplier-active.md §7): one supplier driven
 * {@code created → active} over HTTP, all commands admin-on-behalf through the WS-0 edge. Asserts the
 * linear state machine + the SA8.2 activation gate, per-command SoD, and idempotency. MockMvc over the
 * Testcontainers Postgres (login + admin-seed helpers from {@link AbstractEdgeHttpTest}).
 */
class SupplierOnboardingTest extends AbstractEdgeHttpTest {

    private static final Random RND = new Random();

    private String ops;
    private String compliance;
    private String credit;

    @BeforeEach
    void seedActors() {
        notifier.clear();
        ops = bearerFor(seedAdminWithRoles("ops_executive"));
        compliance = bearerFor(seedAdminWithRoles("compliance_reviewer"));
        credit = bearerFor(seedAdminWithRoles("credit_reviewer"));
    }

    @Test
    void happy_path_drives_a_supplier_from_created_to_active() throws Exception {
        UUID supplier = createSupplier();
        assertThat(statusOf(supplier)).isEqualTo("created");

        send(post("/suppliers/{id}/grant-agency-consent", supplier), ops, supplier,
                Map.of("scope", List.of("listing_intake", "disbursement")));
        send(post("/suppliers/{id}/record-identity-verified", supplier), ops, supplier, Map.of());
        assertThat(statusOf(supplier)).isEqualTo("identity_verified");

        send(post("/suppliers/{id}/submit-kyc", supplier), ops, supplier, Map.of());
        assertThat(statusOf(supplier)).isEqualTo("kyc_submitted");

        send(post("/suppliers/{id}/record-kyc-approved", supplier), compliance, supplier, Map.of());
        assertThat(statusOf(supplier)).isEqualTo("kyc_approved");

        send(post("/suppliers/{id}/submit-financial-profile", supplier), ops, supplier,
                Map.of("top_buyers", List.of(Map.of("buyer_name", "Acme", "annual_turnover_paise", 5_00_00_000L))));
        send(post("/suppliers/{id}/record-credit-review", supplier), credit, supplier,
                Map.of("exposure_cap_paise", 50_00_00_000L, "risk_rating", "BBB")); // ₹5 Cr < ₹10 Cr (no four-eyes)
        assertThat(statusOf(supplier)).isEqualTo("credit_reviewed");

        send(post("/suppliers/{id}/record-maa-signed", supplier), ops, supplier, Map.of());
        assertThat(statusOf(supplier)).isEqualTo("maa_signed");

        send(post("/suppliers/{id}/activate", supplier), ops, supplier, Map.of());
        assertThat(statusOf(supplier)).isEqualTo("active");
    }

    @Test
    void activate_before_maa_signed_is_rejected() throws Exception { // SA8.2 gate
        UUID supplier = createSupplier();
        mvc.perform(withEnvelope(post("/suppliers/{id}/activate", supplier), ops, supplier, Map.of()))
                .andExpect(status().is4xxClientError());
        assertThat(statusOf(supplier)).isEqualTo("created"); // unchanged
    }

    @Test
    void record_credit_review_by_a_non_credit_reviewer_is_403() throws Exception { // SoD
        UUID supplier = createSupplier();
        mvc.perform(withEnvelope(post("/suppliers/{id}/record-credit-review", supplier), ops, supplier,
                        Map.of("exposure_cap_paise", 50_00_00_000L, "risk_rating", "BBB")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));
    }

    @Test
    void replaying_create_yields_a_single_supplier() throws Exception {
        UUID commandId = UUID.randomUUID();
        Map<String, Object> body = createBody();
        MvcResult first = mvc.perform(createRequest(ops, commandId, body)).andExpect(status().isCreated()).andReturn();
        UUID supplier = UUID.fromString(node(first).get("aggregate_id").asText());

        mvc.perform(createRequest(ops, commandId, body)).andExpect(status().isOk()); // replay → 200
        Integer count = jdbc.queryForObject("SELECT count(*) FROM sup_account WHERE supplier_id = ?",
                Integer.class, supplier);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void grant_agency_consent_creates_an_active_consent_row() throws Exception {
        UUID supplier = createSupplier();
        send(post("/suppliers/{id}/grant-agency-consent", supplier), ops, supplier,
                Map.of("scope", List.of("listing_intake")));
        Integer active = jdbc.queryForObject(
                "SELECT count(*) FROM sup_agency_consent WHERE supplier_id = ? AND is_active = TRUE",
                Integer.class, supplier);
        assertThat(active).isEqualTo(1);
    }

    @Test
    void get_supplier_returns_the_aggregate_read() throws Exception {
        UUID supplier = createSupplier();
        mvc.perform(get("/suppliers/{id}", supplier).header("Authorization", "Bearer " + ops))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplier_id").value(supplier.toString()))
                .andExpect(jsonPath("$.status").value("created"));
    }

    // --- review-driven hardening -------------------------------------------------------------------

    @Test
    void a_fractional_exposure_cap_is_rejected_not_truncated() throws Exception { // money is integer paise
        UUID supplier = createSupplier();
        mvc.perform(withEnvelope(post("/suppliers/{id}/record-credit-review", supplier), credit, supplier,
                        Map.of("exposure_cap_paise", 5_000_000.5, "risk_rating", "BBB")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("validation_failed"));
    }

    @Test
    void a_consent_scope_value_with_a_comma_is_stored_as_one_element() throws Exception { // array safety
        UUID supplier = createSupplier();
        send(post("/suppliers/{id}/grant-agency-consent", supplier), ops, supplier,
                Map.of("scope", List.of("intake,with,commas")));
        Integer length = jdbc.queryForObject(
                "SELECT array_length(scope, 1) FROM sup_agency_consent WHERE supplier_id = ?", Integer.class, supplier);
        assertThat(length).isEqualTo(1); // not split into 3 by a built array literal
    }

    @Test
    void kyc_approval_by_the_submitter_is_a_clean_409_not_a_500() throws Exception { // maker ≠ checker (KF.2/C4)
        String dual = bearerFor(seedAdminWithRoles("ops_executive", "compliance_reviewer"));
        UUID supplier = createSupplier(dual);
        send(post("/suppliers/{id}/grant-agency-consent", supplier), dual, supplier, Map.of("scope", List.of("x")));
        send(post("/suppliers/{id}/record-identity-verified", supplier), dual, supplier, Map.of());
        send(post("/suppliers/{id}/submit-kyc", supplier), dual, supplier, Map.of()); // submitted_by = dual

        mvc.perform(withEnvelope(post("/suppliers/{id}/record-kyc-approved", supplier), dual, supplier, Map.of()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("checker_equals_maker"));
        assertThat(statusOf(supplier)).isEqualTo("kyc_submitted"); // not approved
    }

    // --- helpers -----------------------------------------------------------------------------------

    private UUID createSupplier() throws Exception {
        return createSupplier(ops);
    }

    private UUID createSupplier(String bearer) throws Exception {
        MvcResult res = mvc.perform(createRequest(bearer, UUID.randomUUID(), createBody()))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(node(res).get("aggregate_id").asText());
    }

    private MockHttpServletRequestBuilder createRequest(String bearer, UUID commandId, Map<String, Object> body)
            throws Exception {
        return post("/suppliers/create")
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", commandId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
    }

    private Map<String, Object> createBody() {
        // gstin + cin are UNIQUE on sup_account, so every supplier needs its own (real suppliers do too).
        String pan = letters(5) + String.format("%04d", RND.nextInt(10000)) + letters(1);
        return Map.of("legal_name", "Supplier " + UUID.randomUUID(), "constitution_type", "private_limited",
                "pan", pan, "gstin", "27" + pan + "1Z5", "cin", "U72200KA2020PTC" + String.format("%06d", RND.nextInt(1_000_000)));
    }

    private static String letters(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append((char) ('A' + RND.nextInt(26)));
        }
        return sb.toString();
    }

    /** Performs a transition command and asserts 2xx (the happy-path driver). */
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
        return jdbc.queryForObject("SELECT status::text FROM sup_account WHERE supplier_id = ?",
                String.class, supplier);
    }

    private int versionOf(UUID supplier) {
        return jdbc.queryForObject("SELECT aggregate_version FROM sup_account WHERE supplier_id = ?",
                Integer.class, supplier);
    }
}
