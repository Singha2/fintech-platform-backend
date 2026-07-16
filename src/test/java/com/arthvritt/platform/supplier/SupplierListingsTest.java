package com.arthvritt.platform.supplier;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-11 · {@code GET /suppliers/{id}/listings} (S14 admin tracker) — UI_INTEGRATION_BACKEND_SPEC §2. Per-supplier
 * invoice/listing rows with funding progress. Path-scoped, so the response holds only the target supplier's rows.
 */
class SupplierListingsTest extends AbstractEdgeHttpTest {

    private String bearer;

    @BeforeEach
    void seed() {
        bearer = bearerFor(seedAdminWithRoles("ops_executive"));
    }

    @Test
    void lists_the_suppliers_listings_with_funding_progress() throws Exception {
        UUID supplier = UUID.randomUUID();
        UUID listing = seedListing(supplier, "fully_funded", 9_50_000_00L, 9_50_000_00L, true);
        // a different supplier's listing must not appear
        seedListing(UUID.randomUUID(), "live", 5_00_000_00L, 0L, true);

        JsonNode rows = node(mvc.perform(get("/suppliers/{id}/listings", supplier)
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn());

        assertThat(rows.size()).isEqualTo(1);
        JsonNode row = rows.get(0);
        assertThat(row.get("listing_id").asText()).isEqualTo(listing.toString());
        assertThat(row.get("status").asText()).isEqualTo("fully_funded");
        assertThat(row.get("face_value_paise").asLong()).isEqualTo(10_00_000_00L);
        assertThat(row.get("funding_target").asLong()).isEqualTo(9_50_000_00L);
        assertThat(row.get("committed_total").asLong()).isEqualTo(9_50_000_00L);   // funding progress
        assertThat(row.get("rate_bps").asInt()).isEqualTo(1500);
        assertThat(row.hasNonNull("due_date")).isTrue();
        assertThat(row.hasNonNull("created_at")).isTrue();
    }

    @Test
    void unknown_supplier_returns_an_empty_list() throws Exception {
        JsonNode rows = node(mvc.perform(get("/suppliers/{id}/listings", UUID.randomUUID())
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn());
        assertThat(rows.isArray()).isTrue();
        assertThat(rows.size()).isZero();
    }

    /** A minimal invoice + listing for a supplier; returns the listing id. */
    private UUID seedListing(UUID supplier, String status, Long fundingTarget, long committed, boolean priced) {
        UUID buyer = UUID.randomUUID();
        UUID invoice = UUID.randomUUID();
        jdbc.update("INSERT INTO deal_invoice (invoice_id, supplier_id, buyer_id, invoice_number, face_value, "
                        + "invoice_date, tenor_days, due_date) "
                        + "VALUES (?, ?, ?, ?, ?, '2026-01-15', 45, '2026-03-01')",
                invoice, supplier, buyer, "INV-" + UUID.randomUUID(), 10_00_000_00L);
        UUID listing = UUID.randomUUID();
        jdbc.update("INSERT INTO deal_listing (listing_id, invoice_id, supplier_id, buyer_id, status, "
                        + "pricing_snapshot, funding_target, committed_total) "
                        + "VALUES (?, ?, ?, ?, ?::deal_listing_status, ?::jsonb, ?, ?)",
                listing, invoice, supplier, buyer, status,
                priced ? "{\"rate_bps\":1500,\"fee_bps\":200}" : null, fundingTarget, committed);
        return listing;
    }
}
