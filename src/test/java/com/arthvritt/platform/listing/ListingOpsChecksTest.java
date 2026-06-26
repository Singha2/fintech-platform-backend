package com.arthvritt.platform.listing;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M9-C (DL-BE-041) — the operational-check sub-machine: the real DL-027 7-check flow that replaces the WS-4
 * collapsed {@code pass-ops-checks}. Drives invoice {@code submitted → ops_checks_in_progress →
 * {ops_checks_passed → listed | ops_checks_failed}} and listing {@code draft →
 * operational_checks_in_progress → {awaiting_acknowledgment | rejected_operational}} over HTTP, proving:
 * INV.5 (all 7 checks pass before {@code ops_checks_passed}), INV.7 (IRN is vendor-verified via BC17, never
 * self-attested), the incomplete-vs-failed distinction, and SoD.
 */
class ListingOpsChecksTest extends AbstractEdgeHttpTest {

    private static final long FACE = 100_000_000L;
    private static final int TENOR_DAYS = 60;

    // The DL-027 operational-check set. irn_validity is vendor-backed (BC17); the rest are ops-attested.
    private static final String[] OPS_ATTESTED = {
            "eway_bill_match", "buyer_supplier_relationship", "duplicate_check",
            "supplier_exposure_cap", "buyer_limit_headroom", "document_completeness"};

    private String ops;
    private String treasury;
    private UUID supplierId;
    private UUID buyerId;

    @BeforeEach
    void seed() {
        notifier.clear();
        Seeded opsAdmin = seedAdminWithRoles("ops_executive");
        ops = bearerFor(opsAdmin);
        treasury = bearerFor(seedAdminWithRoles("treasury_and_settlement"));
        supplierId = seedActiveSupplier();
        buyerId = seedActiveBuyer(opsAdmin.adminUserId());
        seedPricingBand(buyerId, "31_60d", 1000, 1500, 200);
    }

    @Test
    void all_seven_checks_pass_advances_to_awaiting_acknowledgment_and_listed() throws Exception {
        UUID listing = createListing(null);

        send(post("/listings/{id}/start-ops-checks", listing), ops, listing, Map.of());
        assertThat(listingStatus(listing)).isEqualTo("operational_checks_in_progress");
        assertThat(invoiceStatus(listing)).isEqualTo("ops_checks_in_progress");

        recordAllPassing(listing);
        send(post("/listings/{id}/complete-ops-checks", listing), ops, listing, Map.of());

        assertThat(listingStatus(listing)).isEqualTo("awaiting_acknowledgment");
        assertThat(invoiceStatus(listing)).isEqualTo("listed");
    }

    @Test
    void completing_with_a_missing_check_is_rejected_as_incomplete_and_stays_in_progress() throws Exception {
        UUID listing = createListing(null);
        send(post("/listings/{id}/start-ops-checks", listing), ops, listing, Map.of());
        // Record only the IRN check + five of six ops checks — leave document_completeness unrecorded.
        recordOps(listing, "irn_validity", null);
        for (int i = 0; i < OPS_ATTESTED.length - 1; i++) {
            recordOps(listing, OPS_ATTESTED[i], "passed");
        }
        mvc.perform(withEnvelope(post("/listings/{id}/complete-ops-checks", listing), ops, listing, Map.of()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error_code").value("operational_checks_incomplete"));
        assertThat(listingStatus(listing)).isEqualTo("operational_checks_in_progress");
        assertThat(invoiceStatus(listing)).isEqualTo("ops_checks_in_progress");
    }

    @Test
    void any_failed_check_routes_to_rejected_operational() throws Exception {
        UUID listing = createListing(null);
        send(post("/listings/{id}/start-ops-checks", listing), ops, listing, Map.of());
        recordOps(listing, "irn_validity", null);
        for (String check : OPS_ATTESTED) {
            recordOps(listing, check, check.equals("document_completeness") ? "failed" : "passed");
        }
        send(post("/listings/{id}/complete-ops-checks", listing), ops, listing, Map.of());

        assertThat(listingStatus(listing)).isEqualTo("rejected_operational");
        assertThat(invoiceStatus(listing)).isEqualTo("ops_checks_failed");
    }

    @Test
    void recording_a_check_before_start_is_rejected() throws Exception {
        UUID listing = createListing(null); // still 'draft'
        mvc.perform(withEnvelope(post("/listings/{id}/record-ops-check", listing), ops, listing,
                        Map.of("check_name", "document_completeness", "outcome", "passed")))
                .andExpect(status().is4xxClientError());
        assertThat(listingStatus(listing)).isEqualTo("draft");
    }

    @Test
    void irn_validity_is_vendor_verified_not_self_attested() throws Exception { // INV.7
        UUID listing = createListing("1".repeat(64)); // irn_type domain: exactly 64 chars
        UUID invoiceId = invoiceId(listing);
        send(post("/listings/{id}/start-ops-checks", listing), ops, listing, Map.of());

        // Even with a body outcome of 'failed', a valid IRN passes — the ACL result wins, never the client.
        recordOps(listing, "irn_validity", "failed");
        for (String check : OPS_ATTESTED) {
            recordOps(listing, check, "passed");
        }
        send(post("/listings/{id}/complete-ops-checks", listing), ops, listing, Map.of());
        assertThat(listingStatus(listing)).isEqualTo("awaiting_acknowledgment");

        // A BC17 verify_irn was actually issued for the invoice subject.
        Integer verifications = jdbc.queryForObject(
                "SELECT count(*) FROM gate_verification WHERE subject_id = ? AND api_name = 'verify_irn'",
                Integer.class, invoiceId);
        assertThat(verifications).isGreaterThanOrEqualTo(1);
    }

    @Test
    void recording_an_ops_check_by_a_non_ops_actor_is_403() throws Exception { // SoD
        UUID listing = createListing(null);
        send(post("/listings/{id}/start-ops-checks", listing), ops, listing, Map.of());
        mvc.perform(withEnvelope(post("/listings/{id}/record-ops-check", listing), treasury, listing,
                        Map.of("check_name", "document_completeness", "outcome", "passed")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));
    }

    @Test
    void an_unknown_check_name_is_rejected() throws Exception {
        UUID listing = createListing(null);
        send(post("/listings/{id}/start-ops-checks", listing), ops, listing, Map.of());
        mvc.perform(withEnvelope(post("/listings/{id}/record-ops-check", listing), ops, listing,
                        Map.of("check_name", "not_a_real_check", "outcome", "passed")))
                .andExpect(status().isBadRequest());
    }

    // --- helpers -----------------------------------------------------------------------------------

    private void recordAllPassing(UUID listing) throws Exception {
        recordOps(listing, "irn_validity", null); // no IRN on this invoice → not_applicable (pass)
        for (String check : OPS_ATTESTED) {
            recordOps(listing, check, "passed");
        }
    }

    private void recordOps(UUID listing, String checkName, String outcome) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("check_name", checkName);
        if (outcome != null) {
            body.put("outcome", outcome);
        }
        send(post("/listings/{id}/record-ops-check", listing), ops, listing, body);
    }

    private UUID createListing(String irn) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("supplier_id", supplierId.toString());
        body.put("buyer_id", buyerId.toString());
        body.put("invoice_number", "INV-" + UUID.randomUUID());
        body.put("face_value_paise", FACE);
        body.put("invoice_date", "2026-06-01");
        body.put("tenor_days", TENOR_DAYS);
        if (irn != null) {
            body.put("irn", irn);
        }
        MvcResult res = mvc.perform(post("/listings")
                        .header("Authorization", "Bearer " + ops)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(node(res).get("aggregate_id").asText());
    }

    private void send(MockHttpServletRequestBuilder builder, String bearer, UUID listing, Map<String, Object> body)
            throws Exception {
        mvc.perform(withEnvelope(builder, bearer, listing, body)).andExpect(status().is2xxSuccessful());
    }

    private MockHttpServletRequestBuilder withEnvelope(MockHttpServletRequestBuilder builder, String bearer,
                                                       UUID listing, Map<String, Object> body) throws Exception {
        return builder
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", UUID.randomUUID().toString())
                .header("X-Aggregate-Version", String.valueOf(versionOf(listing)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
    }

    private String listingStatus(UUID listing) {
        return jdbc.queryForObject("SELECT status::text FROM deal_listing WHERE listing_id = ?", String.class, listing);
    }

    private String invoiceStatus(UUID listing) {
        return jdbc.queryForObject("SELECT i.status::text FROM deal_invoice i "
                + "JOIN deal_listing l ON l.invoice_id = i.invoice_id WHERE l.listing_id = ?", String.class, listing);
    }

    private UUID invoiceId(UUID listing) {
        return jdbc.queryForObject("SELECT invoice_id FROM deal_listing WHERE listing_id = ?", UUID.class, listing);
    }

    private int versionOf(UUID listing) {
        return jdbc.queryForObject("SELECT aggregate_version FROM deal_listing WHERE listing_id = ?",
                Integer.class, listing);
    }

    // --- seeding (mirror ListingGoLiveTest upstream context state) ---------------------------------

    private UUID seedActiveSupplier() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sup_account (supplier_id, legal_name, constitution_type, pan, status, "
                        + "credit_exposure_cap_paise) VALUES (?, ?, 'private_limited', ?::pan_type, 'active', ?)",
                id, "Supplier " + id, "ABCDE1234F", 5_00_00_000_00L);
        return id;
    }

    private UUID seedActiveBuyer(UUID nominatedBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO buyer_account (buyer_id, legal_name, status, credit_limit_paise, nominated_by) "
                        + "VALUES (?, ?, 'active', ?, ?)", id, "Buyer " + id, 5_00_00_000_00L, nominatedBy);
        return id;
    }

    private void seedPricingBand(UUID buyer, String bucket, int min, int max, int fee) {
        jdbc.update("INSERT INTO risk_pricing_policy (pricing_band_id, buyer_id, tenor_bucket, "
                        + "rate_range_min_bps, rate_range_max_bps, fee_bps, effective_from) "
                        + "VALUES (?, ?, ?::risk_tenor_bucket, ?, ?, ?, now()::date)",
                UUID.randomUUID(), buyer, bucket, min, max, fee);
    }
}
