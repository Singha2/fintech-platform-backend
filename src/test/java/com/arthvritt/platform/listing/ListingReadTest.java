package com.arthvritt.platform.listing;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-6 · {@code GET /listings} (S5 list, JOIN {@code deal_invoice}) + {@code GET /listings/{id}/ops-checks}
 * (from the {@code check_outcomes} JSONB) — UI_INTEGRATION_BACKEND_SPEC §2. Rows isolated by a random
 * {@code supplier_id} filter so assertions are robust against any other data.
 */
class ListingReadTest extends AbstractEdgeHttpTest {

    private String bearer;

    @BeforeEach
    void seed() {
        bearer = bearerFor(seedAdminWithRoles("ops_executive"));
    }

    @Test
    void list_returns_joined_invoice_fields_and_the_snapshot_rate() throws Exception {
        UUID supplier = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        String number = "INV-" + UUID.randomUUID();
        UUID invoice = seedInvoice(supplier, buyer, number, 10_00_000_00L, 45, null);
        UUID listing = seedListing(invoice, supplier, buyer, "draft", 9_00_000_00L, 1200);

        List<JsonNode> rows = rows(query("supplier_id", supplier.toString()));

        assertThat(rows).hasSize(1);
        JsonNode row = rows.get(0);
        assertThat(row.get("listing_id").asText()).isEqualTo(listing.toString());
        assertThat(row.get("invoice_number").asText()).isEqualTo(number);
        assertThat(row.get("face_value_paise").asLong()).isEqualTo(10_00_000_00L);
        assertThat(row.get("tenor_days").asInt()).isEqualTo(45);
        assertThat(row.get("status").asText()).isEqualTo("draft");
        assertThat(row.get("funding_target").asLong()).isEqualTo(9_00_000_00L);
        assertThat(row.get("rate_bps").asInt()).isEqualTo(1200);   // from pricing_snapshot JSONB
    }

    @Test
    void rate_bps_is_null_without_a_pricing_snapshot() throws Exception {
        UUID supplier = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        UUID invoice = seedInvoice(supplier, buyer, "INV-" + UUID.randomUUID(), 5_00_000_00L, 30, null);
        seedListing(invoice, supplier, buyer, "draft", null, null);

        JsonNode row = rows(query("supplier_id", supplier.toString())).get(0);

        assertThat(row.get("rate_bps").isNull()).isTrue();
        assertThat(row.get("funding_target").isNull()).isTrue();
    }

    @Test
    void the_status_filter_narrows_the_list() throws Exception {
        UUID supplier = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        UUID inv1 = seedInvoice(supplier, buyer, "INV-" + UUID.randomUUID(), 1_00_000_00L, 30, null);
        UUID draft = seedListing(inv1, supplier, buyer, "draft", null, null);
        UUID inv2 = seedInvoice(supplier, buyer, "INV-" + UUID.randomUUID(), 1_00_000_00L, 30, null);
        seedListing(inv2, supplier, buyer, "operational_checks_in_progress", null, null);

        MvcResult res = mvc.perform(get("/listings")
                        .header("Authorization", "Bearer " + bearer)
                        .param("supplier_id", supplier.toString())
                        .param("status", "draft"))
                .andExpect(status().isOk()).andReturn();

        List<JsonNode> rows = rows(res);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("listing_id").asText()).isEqualTo(draft.toString());
    }

    @Test
    void ops_checks_expands_the_recorded_checks_from_the_jsonb() throws Exception {
        UUID supplier = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        String checks = "{\"irn_validity\":{\"outcome\":\"not_applicable\",\"verification_id\":null,"
                + "\"checked_at\":\"2026-01-16T10:00:00Z\"},"
                + "\"duplicate_check\":{\"outcome\":\"passed\",\"verification_id\":null,"
                + "\"checked_at\":\"2026-01-16T10:05:00Z\"}}";
        UUID invoice = seedInvoice(supplier, buyer, "INV-" + UUID.randomUUID(), 1_00_000_00L, 30, checks);
        UUID listing = seedListing(invoice, supplier, buyer, "operational_checks_in_progress", null, null);

        JsonNode rows = node(mvc.perform(get("/listings/{id}/ops-checks", listing)
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn());

        assertThat(rows).hasSize(2);   // ordered by check_name: duplicate_check, irn_validity
        assertThat(rows.get(0).get("check_name").asText()).isEqualTo("duplicate_check");
        assertThat(rows.get(0).get("outcome").asText()).isEqualTo("passed");
        assertThat(rows.get(1).get("check_name").asText()).isEqualTo("irn_validity");
        assertThat(rows.get(1).get("outcome").asText()).isEqualTo("not_applicable");
    }

    @Test
    void ops_checks_for_an_unknown_listing_is_404() throws Exception {
        mvc.perform(get("/listings/{id}/ops-checks", UUID.randomUUID())
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isNotFound());
    }

    // --- helpers ----------------------------------------------------------------------------------------

    private MvcResult query(String param, String value) throws Exception {
        return mvc.perform(get("/listings").header("Authorization", "Bearer " + bearer).param(param, value))
                .andExpect(status().isOk()).andReturn();
    }

    private List<JsonNode> rows(MvcResult res) {
        List<JsonNode> out = new ArrayList<>();
        node(res).forEach(out::add);
        return out;
    }

    private UUID seedInvoice(UUID supplierId, UUID buyerId, String number, long facePaise, int tenor, String checkOutcomes) {
        UUID invoiceId = UUID.randomUUID();
        jdbc.update("INSERT INTO deal_invoice (invoice_id, supplier_id, buyer_id, invoice_number, face_value, "
                        + "invoice_date, tenor_days, due_date, check_outcomes) "
                        + "VALUES (?, ?, ?, ?, ?, '2026-01-15', ?, '2026-03-01', COALESCE(?::jsonb, '{}'::jsonb))",
                invoiceId, supplierId, buyerId, number, facePaise, tenor, checkOutcomes);
        return invoiceId;
    }

    private UUID seedListing(UUID invoiceId, UUID supplierId, UUID buyerId, String status, Long fundingTarget,
                             Integer rateBps) {
        UUID listingId = UUID.randomUUID();
        String snapshot = rateBps == null ? null : "{\"rate_bps\": " + rateBps + ", \"fee_bps\": 200}";
        jdbc.update("INSERT INTO deal_listing (listing_id, invoice_id, supplier_id, buyer_id, status, "
                        + "funding_target, pricing_snapshot) "
                        + "VALUES (?, ?, ?, ?, ?::deal_listing_status, ?, ?::jsonb)",
                listingId, invoiceId, supplierId, buyerId, status, fundingTarget, snapshot);
        return listingId;
    }
}
