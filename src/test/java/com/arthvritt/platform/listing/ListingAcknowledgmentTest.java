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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M9-D (DL-BE-042) — the buyer-acknowledgment branch off {@code awaiting_acknowledgment}. Ack is
 * admin-captured (DL-019): an Ops executive records the buyer's response on their behalf (the real
 * ack-user OTP login is a later buyer-portal slice). Proves: ack gates {@code snapshot-and-ready} (no
 * snapshot without a recorded acknowledgment), the {@code acknowledgment_failed} branch, the BC15
 * notification on {@code request-buyer-ack}, and SoD.
 */
class ListingAcknowledgmentTest extends AbstractEdgeHttpTest {

    private static final long FACE = 100_000_000L;
    private static final int RATE_BPS = 1200;
    private static final int TENOR_DAYS = 60;

    private String ops;
    // M19 DOC.3 (maker ≠ checker): document_completeness must be recorded by someone other than the
    // invoice artifact's uploader (`ops`, below).
    private String ops2;
    private String treasury;
    private UUID supplierId;
    private UUID buyerId;
    private UUID opsAdminId;

    @BeforeEach
    void seed() {
        notifier.clear();
        Seeded opsAdmin = seedAdminWithRoles("ops_executive");
        opsAdminId = opsAdmin.adminUserId();
        ops = bearerFor(opsAdmin);
        ops2 = bearerFor(seedAdminWithRoles("ops_executive"));
        treasury = bearerFor(seedAdminWithRoles("treasury_and_settlement"));
        supplierId = seedActiveSupplier();
        buyerId = seedActiveBuyer(opsAdminId);
        seedPricingBand(buyerId, "31_60d", 1000, 1500, 200);
    }

    @Test
    void recorded_acknowledgment_lets_the_snapshot_proceed() throws Exception {
        UUID listing = awaitingAcknowledgment();
        send(post("/listings/{id}/record-buyer-ack", listing), ops, listing,
                Map.of("outcome", "acknowledged", "method", "email", "evidence_ref", "ACK-DOC-1"));
        assertThat(listingStatus(listing)).isEqualTo("awaiting_acknowledgment");

        send(post("/listings/{id}/snapshot-and-ready", listing), ops, listing, Map.of("rate_bps", RATE_BPS));
        assertThat(listingStatus(listing)).isEqualTo("ready_for_review");
    }

    @Test
    void snapshot_without_a_recorded_acknowledgment_is_rejected() throws Exception {
        UUID listing = awaitingAcknowledgment();
        mvc.perform(withEnvelope(post("/listings/{id}/snapshot-and-ready", listing), ops, listing,
                        Map.of("rate_bps", RATE_BPS)))
                .andExpect(status().is4xxClientError());
        assertThat(listingStatus(listing)).isEqualTo("awaiting_acknowledgment");
    }

    @Test
    void a_failed_acknowledgment_routes_to_acknowledgment_failed_and_blocks_snapshot() throws Exception {
        UUID listing = awaitingAcknowledgment();
        send(post("/listings/{id}/record-buyer-ack", listing), ops, listing,
                Map.of("outcome", "failed", "method", "email"));
        assertThat(listingStatus(listing)).isEqualTo("acknowledgment_failed");

        // No further progress from the terminal-M9 branch.
        mvc.perform(withEnvelope(post("/listings/{id}/snapshot-and-ready", listing), ops, listing,
                        Map.of("rate_bps", RATE_BPS)))
                .andExpect(status().is4xxClientError());
        assertThat(listingStatus(listing)).isEqualTo("acknowledgment_failed");
    }

    @Test
    void request_buyer_ack_notifies_the_buyers_active_ack_user() throws Exception {
        UUID listing = awaitingAcknowledgment();
        UUID ackIdentityId = seedActiveAckUser(buyerId);

        send(post("/listings/{id}/request-buyer-ack", listing), ops, listing, Map.of("sla_hours", 48));

        assertThat(listingStatus(listing)).isEqualTo("awaiting_acknowledgment");
        assertThat(notifier.lastFor(ackIdentityId)).isPresent();
    }

    @Test
    void re_requesting_after_an_acknowledgment_is_rejected_and_preserves_it() throws Exception {
        UUID listing = awaitingAcknowledgment();
        seedActiveAckUser(buyerId);
        send(post("/listings/{id}/record-buyer-ack", listing), ops, listing, Map.of("outcome", "acknowledged"));

        // A resend after the ack is recorded must NOT downgrade buyer_ack back to 'requested'.
        mvc.perform(withEnvelope(post("/listings/{id}/request-buyer-ack", listing), ops, listing,
                        Map.of("sla_hours", 48)))
                .andExpect(status().is4xxClientError());

        // The acknowledgment survives → the snapshot still proceeds.
        send(post("/listings/{id}/snapshot-and-ready", listing), ops, listing, Map.of("rate_bps", RATE_BPS));
        assertThat(listingStatus(listing)).isEqualTo("ready_for_review");
    }

    @Test
    void request_buyer_ack_without_an_active_ack_user_is_rejected() throws Exception {
        UUID listing = awaitingAcknowledgment(); // buyer has no ack user seeded
        mvc.perform(withEnvelope(post("/listings/{id}/request-buyer-ack", listing), ops, listing,
                        Map.of("sla_hours", 48)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void recording_an_acknowledgment_by_a_non_ops_actor_is_403() throws Exception { // SoD
        UUID listing = awaitingAcknowledgment();
        mvc.perform(withEnvelope(post("/listings/{id}/record-buyer-ack", listing), treasury, listing,
                        Map.of("outcome", "acknowledged")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));
    }

    @Test
    void recording_an_acknowledgment_before_ops_checks_pass_is_rejected() throws Exception {
        UUID listing = createListing(); // still 'draft'
        mvc.perform(withEnvelope(post("/listings/{id}/record-buyer-ack", listing), ops, listing,
                        Map.of("outcome", "acknowledged")))
                .andExpect(status().is4xxClientError());
        assertThat(listingStatus(listing)).isEqualTo("draft");
    }

    // --- helpers -----------------------------------------------------------------------------------

    private UUID awaitingAcknowledgment() throws Exception {
        UUID listing = createListing();
        passAllOpsChecks(listing);
        assertThat(listingStatus(listing)).isEqualTo("awaiting_acknowledgment");
        return listing;
    }

    private void passAllOpsChecks(UUID listing) throws Exception {
        send(post("/listings/{id}/start-ops-checks", listing), ops, listing, Map.of());
        uploadAndAttachInvoicePdf(listing, ops); // M19: document_completeness needs a real, stored artifact
        send(post("/listings/{id}/record-ops-check", listing), ops, listing, Map.of("check_name", "irn_validity"));
        for (String check : new String[]{"eway_bill_match", "buyer_supplier_relationship", "duplicate_check",
                "supplier_exposure_cap", "buyer_limit_headroom"}) {
            send(post("/listings/{id}/record-ops-check", listing), ops, listing,
                    Map.of("check_name", check, "outcome", "passed"));
        }
        // DOC.3: recorder (ops2) must differ from the artifact's uploader (ops, above).
        send(post("/listings/{id}/record-ops-check", listing), ops2, listing,
                Map.of("check_name", "document_completeness", "outcome", "passed"));
        send(post("/listings/{id}/complete-ops-checks", listing), ops, listing, Map.of());
    }

    /**
     * M19: upload a PDF via the M18 {@code /documents} API and attach it to the listing's invoice —
     * the `document_completeness` ops-check now requires a real, stored artifact (DOC.2).
     */
    private UUID uploadAndAttachInvoicePdf(UUID listing, String uploaderBearer) throws Exception {
        byte[] pdf = ("%PDF-1.4\ninvoice::" + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String initBody = json.writeValueAsString(Map.of(
                "kind", "invoice", "content_type", "application/pdf", "declared_size", pdf.length));
        MvcResult init = mvc.perform(post("/documents")
                        .header("Authorization", "Bearer " + uploaderBearer)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(initBody))
                .andExpect(status().is2xxSuccessful()).andReturn();
        UUID docId = UUID.fromString(node(init).get("document_id").asText());
        mvc.perform(put("/documents/{id}/content", docId).header("Authorization", "Bearer " + uploaderBearer)
                .contentType(MediaType.APPLICATION_PDF).content(pdf)).andExpect(status().is2xxSuccessful());
        mvc.perform(post("/documents/{id}/finalize", docId).header("Authorization", "Bearer " + uploaderBearer))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(post("/listings/{id}/invoice-documents", listing)
                        .header("Authorization", "Bearer " + uploaderBearer)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("document_id", docId.toString()))))
                .andExpect(status().is2xxSuccessful());
        return docId;
    }

    private UUID createListing() throws Exception {
        MvcResult res = mvc.perform(post("/listings")
                        .header("Authorization", "Bearer " + ops)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "supplier_id", supplierId.toString(), "buyer_id", buyerId.toString(),
                                "invoice_number", "INV-" + UUID.randomUUID(), "face_value_paise", FACE,
                                "invoice_date", "2026-06-01", "tenor_days", TENOR_DAYS))))
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

    private int versionOf(UUID listing) {
        return jdbc.queryForObject("SELECT aggregate_version FROM deal_listing WHERE listing_id = ?",
                Integer.class, listing);
    }

    // --- seeding -----------------------------------------------------------------------------------

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

    /** Designates an active OTP-only acknowledgment user for the buyer; returns its auth identity id. */
    private UUID seedActiveAckUser(UUID buyer) {
        UUID identityId = UUID.randomUUID();
        String email = "ack-" + identityId + "@example.com";
        jdbc.update("INSERT INTO auth_identity (identity_id, kind, email, phone_e164, display_name, status) "
                        + "VALUES (?, 'acknowledgment_user'::identity_kind_enum, ?, ?, ?, 'active'::identity_status_enum)",
                identityId, email, "+919800000000", "Ack User");
        jdbc.update("INSERT INTO buyer_ack_user "
                        + "(ack_user_id, buyer_id, identity_id, display_name, email, phone, is_active, designated_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, TRUE, ?)",
                UUID.randomUUID(), buyer, identityId, "Ack User", email, "+919800000000", opsAdminId);
        return identityId;
    }

    private void seedPricingBand(UUID buyer, String bucket, int min, int max, int fee) {
        jdbc.update("INSERT INTO risk_pricing_policy (pricing_band_id, buyer_id, tenor_bucket, "
                        + "rate_range_min_bps, rate_range_max_bps, fee_bps, effective_from) "
                        + "VALUES (?, ?, ?::risk_tenor_bucket, ?, ?, ?, now()::date)",
                UUID.randomUUID(), buyer, bucket, min, max, fee);
    }
}
