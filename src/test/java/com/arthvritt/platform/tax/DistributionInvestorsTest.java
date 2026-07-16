package com.arthvritt.platform.tax;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-8 · {@code GET /listings/{id}/distribution/investors} (S8 per-investor breakdown) —
 * UI_INTEGRATION_BACKEND_SPEC §2. Additive read over {@code tax_tds_deduction} (the same table
 * {@code TaxQueryController} reads FY-wide) filtered to one listing. Rows isolated by unique listing id.
 */
class DistributionInvestorsTest extends AbstractEdgeHttpTest {

    private String bearer;

    @BeforeEach
    void seed() {
        bearer = bearerFor(seedAdminWithRoles("treasury_and_settlement"));
    }

    @Test
    void lists_the_per_investor_gross_tds_fee_net_split_ordered_by_investor() throws Exception {
        UUID listing = UUID.randomUUID();
        UUID inv = seedTds(listing, 1_00_000_00L, 10_000_00L, 5_000_00L, "CHLN-001");
        // a row for a different listing must not leak into this listing's breakdown
        seedTds(UUID.randomUUID(), 9_99_999_00L, 0L, 0L, "CHLN-OTHER");

        JsonNode rows = node(mvc.perform(get("/listings/{id}/distribution/investors", listing)
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn());

        assertThat(rows.size()).isEqualTo(1);
        JsonNode row = rows.get(0);
        assertThat(row.get("investor_id").asText()).isEqualTo(inv.toString());
        assertThat(row.get("gross_paise").asLong()).isEqualTo(1_00_000_00L);
        assertThat(row.get("tds_amount_paise").asLong()).isEqualTo(10_000_00L);
        assertThat(row.get("fee_paise").asLong()).isEqualTo(5_000_00L);
        assertThat(row.get("net_paise").asLong()).isEqualTo(85_000_00L);   // gross - tds - fee
        assertThat(row.get("challan_ref").asText()).isEqualTo("CHLN-001");
        // PII / internal ids are not surfaced
        assertThat(row.has("payout_instruction_id")).isFalse();
    }

    @Test
    void a_listing_with_no_distribution_returns_an_empty_list() throws Exception {
        JsonNode rows = node(mvc.perform(get("/listings/{id}/distribution/investors", UUID.randomUUID())
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn());
        assertThat(rows.isArray()).isTrue();
        assertThat(rows.size()).isZero();
    }

    /** A per-investor TDS/distribution row (no enforced FKs — logical BC references). Returns the investor id. */
    private UUID seedTds(UUID listingId, long gross, long tds, long fee, String challan) {
        UUID investorId = UUID.randomUUID();
        jdbc.update("INSERT INTO tax_tds_deduction (tds_deduction_id, investor_id, listing_id, fy_code, "
                        + "payout_instruction_id, challan_ref, gross_paise, tds_amount_paise, fee_paise, net_paise) "
                        + "VALUES (?, ?, ?, 'FY2026-27', ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), investorId, listingId, UUID.randomUUID(), challan,
                gross, tds, fee, gross - tds - fee);
        return investorId;
    }
}
