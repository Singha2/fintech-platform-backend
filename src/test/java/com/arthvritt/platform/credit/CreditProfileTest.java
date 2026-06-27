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
 * M6-B (DL-BE-061) — buyer/supplier credit profiles. Setting a profile upserts the authoritative
 * {@code risk_*_profile} AND snapshots the value to the {@code buyer_account}/{@code sup_account} column the
 * M9-A query ports read. A limit/cap over ₹10 Cr is rejected (BCP.2/SCP.2 four-eyes deferred). Credit Reviewer.
 */
class CreditProfileTest extends AbstractEdgeHttpTest {

    private static final long OVER_10_CR = 10_000_000_001L; // 1 paise over the four-eyes threshold

    private String credit;
    private UUID buyerId;
    private UUID supplierId;

    @BeforeEach
    void seed() {
        notifier.clear();
        Seeded creditAdmin = seedAdminWithRoles("credit_reviewer");
        credit = bearerFor(creditAdmin);
        buyerId = seedBuyer(creditAdmin.adminUserId());
        supplierId = seedSupplier();
    }

    @Test
    void setting_a_buyer_profile_upserts_and_snapshots_the_limit() throws Exception {
        long limit = 5_000_000_000L; // ₹5 Cr — under the threshold
        mvc.perform(buyerProfile(credit, buyerId, limit)).andExpect(status().is2xxSuccessful());

        assertThat(longOf("SELECT credit_limit FROM risk_buyer_profile WHERE buyer_id = ?", buyerId)).isEqualTo(limit);
        assertThat(longOf("SELECT credit_limit_paise FROM buyer_account WHERE buyer_id = ?", buyerId)).isEqualTo(limit);
    }

    @Test
    void a_buyer_limit_over_10_cr_is_rejected_four_eyes_deferred() throws Exception { // BCP.2
        mvc.perform(buyerProfile(credit, buyerId, OVER_10_CR))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error_code").value("four_eyes_required"));
        Integer profiles = jdbc.queryForObject("SELECT count(*) FROM risk_buyer_profile WHERE buyer_id = ?",
                Integer.class, buyerId);
        assertThat(profiles).isZero();
    }

    @Test
    void setting_a_supplier_profile_upserts_and_snapshots_the_cap() throws Exception {
        long cap = 3_000_000_000L; // ₹3 Cr
        mvc.perform(supplierProfile(credit, supplierId, cap)).andExpect(status().is2xxSuccessful());

        assertThat(longOf("SELECT exposure_cap FROM risk_supplier_profile WHERE supplier_id = ?", supplierId))
                .isEqualTo(cap);
        assertThat(longOf("SELECT credit_exposure_cap_paise FROM sup_account WHERE supplier_id = ?", supplierId))
                .isEqualTo(cap);
    }

    @Test
    void a_supplier_cap_over_10_cr_is_rejected_four_eyes_deferred() throws Exception { // SCP.2
        mvc.perform(supplierProfile(credit, supplierId, OVER_10_CR))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error_code").value("four_eyes_required"));
    }

    @Test
    void a_non_integral_supplier_cap_is_rejected() throws Exception { // money-safety: BigInteger overflow not truncated
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("risk_rating", "BBB");
        body.put("exposure_cap_paise", new java.math.BigInteger("18446744073709551621")); // 2^64+5, overflows long
        mvc.perform(post("/credit/suppliers/{id}/profile", supplierId)
                        .header("Authorization", "Bearer " + credit)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().is4xxClientError());
        Integer profiles = jdbc.queryForObject("SELECT count(*) FROM risk_supplier_profile WHERE supplier_id = ?",
                Integer.class, supplierId);
        assertThat(profiles).isZero();
    }

    @Test
    void set_buyer_profile_by_a_non_credit_actor_is_403() throws Exception { // SoD
        String ops = bearerFor(seedAdminWithRoles("ops_executive"));
        mvc.perform(buyerProfile(ops, buyerId, 5_000_000_000L))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));
    }

    // --- helpers -----------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder buyerProfile(String bearer, UUID buyer, long limitPaise) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sector", "manufacturing");
        body.put("rating_source", "internal");
        body.put("rating", "BBB");
        body.put("credit_limit_paise", limitPaise);
        body.put("tenor_cap_days", 90);
        return post("/credit/buyers/{id}/profile", buyer)
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
    }

    private MockHttpServletRequestBuilder supplierProfile(String bearer, UUID supplier, long capPaise) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("risk_rating", "BBB");
        body.put("exposure_cap_paise", capPaise);
        return post("/credit/suppliers/{id}/profile", supplier)
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
    }

    private long longOf(String sql, UUID id) {
        return jdbc.queryForObject(sql, Long.class, id);
    }

    private UUID seedBuyer(UUID nominatedBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO buyer_account (buyer_id, legal_name, status, nominated_by) "
                + "VALUES (?, ?, 'active', ?)", id, "Buyer " + id, nominatedBy);
        return id;
    }

    private UUID seedSupplier() {
        UUID id = UUID.randomUUID();
        String pan = pan(id); // UNIQUE on sup_account — derive from the id so the suite never collides
        jdbc.update("INSERT INTO sup_account (supplier_id, legal_name, constitution_type, pan, gstin, status) "
                        + "VALUES (?, ?, 'private_limited', ?::pan_type, ?::gstin_type, 'active')",
                id, "Supplier " + id, pan, "27" + pan + "1Z5");
        return id;
    }

    /** A unique, format-valid PAN (5 letters + 4 digits + 1 letter) derived from the id. */
    private static String pan(UUID id) {
        long hi = id.getMostSignificantBits();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append((char) ('A' + (int) Math.floorMod(hi >> (i * 5), 26)));
        }
        return sb.append(String.format("%04d", (int) Math.floorMod(id.getLeastSignificantBits(), 10000)))
                .append('F').toString();
    }
}
