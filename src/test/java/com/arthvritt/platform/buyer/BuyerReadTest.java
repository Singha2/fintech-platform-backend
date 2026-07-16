package com.arthvritt.platform.buyer;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-5 · {@code GET /buyers} (S4 list) + {@code GET /credit/buyers/{id}/pricing-bands} (UI_INTEGRATION_BACKEND_SPEC
 * §2). Additive reads over {@code buyer_account} / {@code risk_pricing_policy}; band {@code status} is derived
 * from {@code superseded_by}. Buyer rows are isolated by a unique legal-name marker via {@code ?q=}.
 */
class BuyerReadTest extends AbstractEdgeHttpTest {

    private static final Random RND = new Random();
    private String bearer;
    private UUID nominatedBy;

    @BeforeEach
    void seed() {
        Seeded admin = seedAdminWithRoles("credit_reviewer");
        bearer = bearerFor(admin);
        nominatedBy = admin.adminUserId();
    }

    @Test
    void list_returns_matching_buyers_with_their_display_columns() throws Exception {
        String marker = marker();
        UUID active = seedBuyer(marker + " Acme Corp", "active", "manufacturing", 5_00_00_000_00L);
        UUID nominated = seedBuyer(marker + " Nova Ltd", "nominated", null, null);

        List<JsonNode> rows = rows(query(marker, null));

        assertThat(idsOf(rows)).containsExactlyInAnyOrder(active.toString(), nominated.toString());
        JsonNode acme = rows.stream().filter(r -> r.get("buyer_id").asText().equals(active.toString()))
                .findFirst().orElseThrow();
        assertThat(acme.get("legal_name").asText()).contains("Acme Corp");
        assertThat(acme.get("sector").asText()).isEqualTo("manufacturing");
        assertThat(acme.get("status").asText()).isEqualTo("active");
        assertThat(acme.get("credit_limit_paise").asLong()).isEqualTo(5_00_00_000_00L);
        assertThat(acme.has("mca_cin")).isTrue();
        assertThat(acme.has("gstin")).isTrue();
    }

    @Test
    void the_status_filter_narrows_the_list() throws Exception {
        String marker = marker();
        UUID active = seedBuyer(marker + " Live Co", "active", "retail", 1_00_00_000_00L);
        seedBuyer(marker + " Pending Co", "nominated", null, null);

        List<JsonNode> activeRows = rows(query(marker, "active"));

        assertThat(idsOf(activeRows)).containsExactly(active.toString());
    }

    @Test
    void pricing_bands_are_listed_active_for_a_buyer() throws Exception {
        UUID buyer = seedBuyer(marker() + " Banded Co", "active", "logistics", 2_00_00_000_00L);
        seedPricingBand(buyer, "31_60d", 1000, 1500, 200);

        JsonNode rows = node(mvc.perform(get("/credit/buyers/{id}/pricing-bands", buyer)
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn());

        assertThat(rows).hasSize(1);
        JsonNode band = rows.get(0);
        assertThat(band.get("tenor_bucket").asText()).isEqualTo("31_60d");
        assertThat(band.get("rate_range_min_bps").asInt()).isEqualTo(1000);
        assertThat(band.get("rate_range_max_bps").asInt()).isEqualTo(1500);
        assertThat(band.get("fee_bps").asInt()).isEqualTo(200);
        assertThat(band.get("status").asText()).isEqualTo("active");   // superseded_by IS NULL
        assertThat(band.hasNonNull("effective_from")).isTrue();
    }

    @Test
    void pricing_bands_for_an_unknown_buyer_is_an_empty_list() throws Exception {
        JsonNode rows = node(mvc.perform(get("/credit/buyers/{id}/pricing-bands", UUID.randomUUID())
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn());

        assertThat(rows.isArray()).isTrue();
        assertThat(rows).isEmpty();
    }

    // --- helpers ----------------------------------------------------------------------------------------

    private MvcResult query(String q, String status) throws Exception {
        var req = get("/buyers").header("Authorization", "Bearer " + bearer).param("q", q);
        if (status != null) {
            req = req.param("status", status);
        }
        return mvc.perform(req).andExpect(status().isOk()).andReturn();
    }

    private List<JsonNode> rows(MvcResult res) {
        List<JsonNode> out = new ArrayList<>();
        node(res).forEach(out::add);
        return out;
    }

    private static List<String> idsOf(List<JsonNode> rows) {
        List<String> ids = new ArrayList<>();
        rows.forEach(r -> ids.add(r.get("buyer_id").asText()));
        return ids;
    }

    private UUID seedBuyer(String legalName, String status, String sector, Long creditLimitPaise) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO buyer_account (buyer_id, legal_name, sector, status, credit_limit_paise, nominated_by) "
                        + "VALUES (?, ?, ?, ?::buyer_account_status, ?, ?)",
                id, legalName, sector, status, creditLimitPaise, nominatedBy);
        return id;
    }

    private void seedPricingBand(UUID buyer, String bucket, int min, int max, int fee) {
        jdbc.update("INSERT INTO risk_pricing_policy (pricing_band_id, buyer_id, tenor_bucket, "
                        + "rate_range_min_bps, rate_range_max_bps, fee_bps, effective_from) "
                        + "VALUES (?, ?, ?::risk_tenor_bucket, ?, ?, ?, now()::date)",
                UUID.randomUUID(), buyer, bucket, min, max, fee);
    }

    private static String marker() {
        return "MK" + Long.toHexString(RND.nextLong() & 0xFFFFFFFFL).toUpperCase();
    }
}
