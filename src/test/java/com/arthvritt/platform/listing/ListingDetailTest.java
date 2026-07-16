package com.arthvritt.platform.listing;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-10 · {@code GET /listings/{id}/detail} (S12 rich admin detail) — UI_INTEGRATION_BACKEND_SPEC §2. A new
 * read-model composing listing + pricing snapshot + virtual account + invoice + buyer + supplier. Separate from
 * the frozen thin {@code GET /listings/{id}}. Rows isolated by unique ids per test.
 */
class ListingDetailTest extends AbstractEdgeHttpTest {

    private String bearer;
    private UUID admin;   // nominated_by / a real admin_user for the buyer FK

    @BeforeEach
    void seed() {
        Seeded a = seedAdminWithRoles("ops_executive");
        bearer = bearerFor(a);
        admin = a.adminUserId();
    }

    @Test
    void composes_the_full_read_model_when_priced_with_a_virtual_account() throws Exception {
        UUID supplier = seedSupplier("Acme Exports Pvt Ltd");
        UUID buyer = seedBuyer("Globex Ltd");
        UUID invoice = seedInvoice(supplier, buyer);
        UUID listing = seedListing(invoice, supplier, buyer, "live", true);
        seedVirtualAccount(listing);

        JsonNode d = node(mvc.perform(get("/listings/{id}/detail", listing)
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn());

        assertThat(d.get("listing_id").asText()).isEqualTo(listing.toString());
        assertThat(d.get("status").asText()).isEqualTo("live");
        assertThat(d.get("committed_total").asLong()).isZero();

        assertThat(d.get("pricing_snapshot").get("rate_bps").asInt()).isEqualTo(1500);
        assertThat(d.get("pricing_snapshot").get("fee_bps").asInt()).isEqualTo(200);

        assertThat(d.get("virtual_account").get("account_no").asText()).isEqualTo("VA0001234567");
        assertThat(d.get("virtual_account").get("ifsc").asText()).isEqualTo("HDFC0000001");

        assertThat(d.get("invoice").get("invoice_number").asText()).isNotBlank();
        assertThat(d.get("invoice").get("face_value_paise").asLong()).isEqualTo(10_00_000_00L);
        assertThat(d.get("invoice").get("tenor_days").asInt()).isEqualTo(45);

        assertThat(d.get("buyer").get("buyer_id").asText()).isEqualTo(buyer.toString());
        assertThat(d.get("buyer").get("legal_name").asText()).isEqualTo("Globex Ltd");

        assertThat(d.get("supplier").get("supplier_id").asText()).isEqualTo(supplier.toString());
        assertThat(d.get("supplier").get("legal_name").asText()).isEqualTo("Acme Exports Pvt Ltd");
        assertThat(d.get("supplier").get("pan").asText()).isEqualTo("AAAAA1111A");
    }

    @Test
    void nulls_pricing_and_virtual_account_before_they_exist() throws Exception {
        UUID supplier = seedSupplier("Draft Supplier");
        UUID buyer = seedBuyer("Draft Buyer");
        UUID invoice = seedInvoice(supplier, buyer);
        UUID listing = seedListing(invoice, supplier, buyer, "draft", false);   // no snapshot, no VA

        JsonNode d = node(mvc.perform(get("/listings/{id}/detail", listing)
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn());

        assertThat(d.get("pricing_snapshot").isNull()).isTrue();
        assertThat(d.get("virtual_account").isNull()).isTrue();
        assertThat(d.get("va_id").isNull()).isTrue();
        // the counterparty + invoice objects are still present
        assertThat(d.get("invoice").has("invoice_number")).isTrue();
        assertThat(d.get("buyer").has("legal_name")).isTrue();
    }

    @Test
    void unknown_listing_is_404() throws Exception {
        mvc.perform(get("/listings/{id}/detail", UUID.randomUUID())
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isNotFound());
    }

    // --- seed helpers -----------------------------------------------------------------------------------

    private UUID seedSupplier(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sup_account (supplier_id, legal_name, constitution_type, pan, status) "
                        + "VALUES (?, ?, 'private_limited'::sup_constitution_type, 'AAAAA1111A', "
                        + "'active'::sup_account_status)",
                id, name);
        return id;
    }

    private UUID seedBuyer(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO buyer_account (buyer_id, legal_name, status, nominated_by) "
                        + "VALUES (?, ?, 'active'::buyer_account_status, ?)",
                id, name, admin);
        return id;
    }

    private UUID seedInvoice(UUID supplier, UUID buyer) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO deal_invoice (invoice_id, supplier_id, buyer_id, invoice_number, face_value, "
                        + "invoice_date, tenor_days, due_date) "
                        + "VALUES (?, ?, ?, ?, ?, '2026-01-15', 45, '2026-03-01')",
                id, supplier, buyer, "INV-" + UUID.randomUUID(), 10_00_000_00L);
        return id;
    }

    /** A listing; when {@code priced}, stamps a pricing_snapshot + a va_id (the VA row itself is seeded separately). */
    private UUID seedListing(UUID invoice, UUID supplier, UUID buyer, String status, boolean priced) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO deal_listing (listing_id, invoice_id, supplier_id, buyer_id, status, "
                        + "pricing_snapshot, funding_target) "
                        + "VALUES (?, ?, ?, ?, ?::deal_listing_status, ?::jsonb, ?)",
                id, invoice, supplier, buyer, status,
                priced ? "{\"rate_bps\":1500,\"fee_bps\":200,\"pricing_band_id\":\"band-1\"}" : null,
                priced ? 9_50_000_00L : null);
        return id;
    }

    private void seedVirtualAccount(UUID listing) {
        UUID vaId = UUID.randomUUID();
        jdbc.update("INSERT INTO cash_virtual_account (va_id, listing_id, status, account_no, ifsc) "
                        + "VALUES (?, ?, 'created'::cash_virtual_account_status, 'VA0001234567', 'HDFC0000001')",
                vaId, listing);
        jdbc.update("UPDATE deal_listing SET va_id = ? WHERE listing_id = ?", vaId, listing);
    }
}
