package com.arthvritt.platform.credit;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M6-A (DL-BE-060) — SetPricingBand, the BC3 write side for {@code risk_pricing_policy} (previously only
 * test-seeded; M9 listing reads the active band via the query port). Proves: a created band is active
 * (PB.2 partial-unique), an invalid rate range is rejected (PB.1), a re-price for an existing
 * {@code (buyer, tenor_bucket)} is rejected (supersession PB.3 deferred), and SoD (Credit Reviewer only).
 */
class CreditPricingBandTest extends AbstractEdgeHttpTest {

    private String credit;
    private UUID buyerId;

    @BeforeEach
    void seed() {
        notifier.clear();
        Seeded creditAdmin = seedAdminWithRoles("credit_reviewer");
        credit = bearerFor(creditAdmin);
        buyerId = seedBuyer(creditAdmin.adminUserId());
    }

    @Test
    void creating_a_pricing_band_makes_it_active() throws Exception {
        mvc.perform(setBand(credit, band(buyerId, "31_60d", 1000, 1500, 200)))
                .andExpect(status().isCreated());

        Integer active = jdbc.queryForObject("SELECT count(*) FROM risk_pricing_policy "
                + "WHERE buyer_id = ? AND tenor_bucket = '31_60d' AND superseded_by IS NULL", Integer.class, buyerId);
        assertThat(active).isEqualTo(1);
    }

    @Test
    void an_invalid_rate_range_is_rejected() throws Exception { // PB.1 (min > max)
        mvc.perform(setBand(credit, band(buyerId, "31_60d", 1500, 1000, 200)))
                .andExpect(status().is4xxClientError());
        assertThat(bandCount()).isZero();
    }

    @Test
    void a_second_active_band_for_the_same_buyer_tenor_is_rejected() throws Exception { // PB.2 / PB.3 deferred
        mvc.perform(setBand(credit, band(buyerId, "31_60d", 1000, 1500, 200))).andExpect(status().isCreated());
        mvc.perform(setBand(credit, band(buyerId, "31_60d", 1100, 1400, 150)))
                .andExpect(status().is4xxClientError());
        assertThat(bandCount()).isEqualTo(1);
    }

    @Test
    void a_bps_value_over_the_ceiling_is_rejected() throws Exception { // bps_type domain 0..100000 -> clean 4xx, not 500
        mvc.perform(setBand(credit, band(buyerId, "31_60d", 1000, 200_000, 200)))
                .andExpect(status().is4xxClientError());
        assertThat(bandCount()).isZero();
    }

    @Test
    void a_malformed_effective_from_is_rejected() throws Exception { // clean 4xx, not a DB 500
        Map<String, Object> body = band(buyerId, "31_60d", 1000, 1500, 200);
        body.put("effective_from", "2026-13-99");
        mvc.perform(setBand(credit, body)).andExpect(status().is4xxClientError());
        assertThat(bandCount()).isZero();
    }

    @Test
    void set_pricing_band_by_a_non_credit_actor_is_403() throws Exception { // SoD
        String ops = bearerFor(seedAdminWithRoles("ops_executive"));
        mvc.perform(setBand(ops, band(buyerId, "31_60d", 1000, 1500, 200)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));
    }

    // --- helpers -----------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder setBand(String bearer, Map<String, Object> body) throws Exception {
        return post("/credit/pricing-bands")
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
    }

    private static Map<String, Object> band(UUID buyer, String bucket, int min, int max, int fee) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("buyer_id", buyer.toString());
        m.put("tenor_bucket", bucket);
        m.put("rate_range_min_bps", min);
        m.put("rate_range_max_bps", max);
        m.put("fee_bps", fee);
        return m;
    }

    private int bandCount() {
        return jdbc.queryForObject("SELECT count(*) FROM risk_pricing_policy WHERE buyer_id = ?",
                Integer.class, buyerId);
    }

    private UUID seedBuyer(UUID nominatedBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO buyer_account (buyer_id, legal_name, status, credit_limit_paise, nominated_by) "
                        + "VALUES (?, ?, 'active', ?, ?)", id, "Buyer " + id, 5_00_00_000_00L, nominatedBy);
        return id;
    }
}
