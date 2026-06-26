package com.arthvritt.platform.settlement;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M13-A (DL-BE-054) — maturity recording. The buyer's full repayment (== invoice face_value) matures a
 * {@code disbursed} listing to {@code matured_payment_received} ({@code Listing.Matured}). An under-payment
 * is a maturity shortfall (→ Collections, M14, deferred) and is rejected.
 */
class ListingMaturityTest extends AbstractEdgeHttpTest {

    private static final long FACE = 60_000_000L; // ₹6L invoice face value (the buyer repays this at maturity)

    private String ops;
    private UUID listingId;

    @BeforeEach
    void seed() {
        notifier.clear();
        ops = bearerFor(seedAdminWithRoles("ops_executive"));
        listingId = seedDisbursedListing();
    }

    @Test
    void a_full_repayment_matures_the_listing() throws Exception {
        send(post("/listings/{id}/record-maturity", listingId), ops,
                Map.of("amount_paise", FACE, "utr", "UTR-" + UUID.randomUUID()));

        assertThat(listingStatus()).isEqualTo("matured_payment_received");
        assertThat(envelopes("listing.Listing.Matured")).isEqualTo(1);
    }

    @Test
    void recording_maturity_on_a_non_disbursed_listing_is_rejected() throws Exception {
        jdbc.update("UPDATE deal_listing SET status = 'fully_funded' WHERE listing_id = ?", listingId);
        mvc.perform(withEnvelope(post("/listings/{id}/record-maturity", listingId), ops,
                        Map.of("amount_paise", FACE, "utr", "UTR-x")))
                .andExpect(status().is4xxClientError());
        assertThat(listingStatus()).isEqualTo("fully_funded");
    }

    @Test
    void an_under_payment_is_rejected_as_a_maturity_shortfall() throws Exception {
        mvc.perform(withEnvelope(post("/listings/{id}/record-maturity", listingId), ops,
                        Map.of("amount_paise", FACE - 1_000_000L, "utr", "UTR-x")))
                .andExpect(status().is4xxClientError());
        assertThat(listingStatus()).isEqualTo("disbursed"); // unchanged
    }

    @Test
    void recording_maturity_is_idempotent_on_command_id() throws Exception {
        UUID commandId = UUID.randomUUID();
        mvc.perform(post("/listings/{id}/record-maturity", listingId)
                        .header("Authorization", "Bearer " + ops)
                        .header("X-Command-Id", commandId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("amount_paise", FACE, "utr", "UTR-1"))))
                .andExpect(status().is2xxSuccessful());
        // Replay the same command_id → gateway returns the original envelope, no second transition.
        mvc.perform(post("/listings/{id}/record-maturity", listingId)
                        .header("Authorization", "Bearer " + ops)
                        .header("X-Command-Id", commandId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("amount_paise", FACE, "utr", "UTR-1"))))
                .andExpect(status().is2xxSuccessful());

        assertThat(listingStatus()).isEqualTo("matured_payment_received");
        assertThat(envelopes("listing.Listing.Matured")).isEqualTo(1); // counted once
    }

    @Test
    void record_maturity_by_a_non_ops_actor_is_403() throws Exception { // SoD
        String treasury = bearerFor(seedAdminWithRoles("treasury_and_settlement"));
        mvc.perform(withEnvelope(post("/listings/{id}/record-maturity", listingId), treasury,
                        Map.of("amount_paise", FACE, "utr", "UTR-x")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));
        assertThat(listingStatus()).isEqualTo("disbursed");
    }

    // --- helpers -----------------------------------------------------------------------------------

    private void send(MockHttpServletRequestBuilder builder, String bearer, Map<String, Object> body) throws Exception {
        mvc.perform(withEnvelope(builder, bearer, body)).andExpect(status().is2xxSuccessful());
    }

    private MockHttpServletRequestBuilder withEnvelope(MockHttpServletRequestBuilder builder, String bearer,
                                                       Map<String, Object> body) throws Exception {
        return builder
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
    }

    private String listingStatus() {
        return jdbc.queryForObject("SELECT status::text FROM deal_listing WHERE listing_id = ?", String.class, listingId);
    }

    private int envelopes(String eventType) {
        return jdbc.queryForObject("SELECT count(*) FROM sys_audit_event WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, eventType, listingId);
    }

    private UUID seedDisbursedListing() {
        UUID listing = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        jdbc.update("INSERT INTO deal_invoice (invoice_id, supplier_id, buyer_id, invoice_number, face_value, "
                        + "invoice_date, tenor_days, due_date, status) "
                        + "VALUES (?, ?, ?, ?, ?, '2026-06-01', 60, '2026-07-31', 'listed')",
                invoiceId, supplierId, buyerId, "INV-" + invoiceId, FACE);
        jdbc.update("INSERT INTO deal_listing (listing_id, invoice_id, supplier_id, buyer_id, status, "
                        + "funding_target, committed_total, all_signed) "
                        + "VALUES (?, ?, ?, ?, 'disbursed', ?, ?, TRUE)",
                listing, invoiceId, supplierId, buyerId, 57_000_000L, 57_000_000L);
        return listing;
    }
}
