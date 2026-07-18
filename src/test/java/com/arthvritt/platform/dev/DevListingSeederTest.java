package com.arthvritt.platform.dev;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DL-BE-086 — the dev-only {@code /dev/seed-listing} helper. Runs under the {@code dev} profile so
 * {@link DevListingSeeder} + {@link DevController} + {@link DevDataSeeder} are wired (a distinct profile ⇒ its
 * own cached context ⇒ a fresh Testcontainers Postgres, on which {@code DevDataSeeder} seeds the DEV
 * supplier/buyer/investor + admins at boot). Proves the seeded state is valid and, crucially, that each UI
 * money-flow <b>write</b> succeeds against it over the real command endpoints — the DoD the brief specifies.
 */
@ActiveProfiles("dev")
class DevListingSeederTest extends AbstractEdgeHttpTest {

    @Autowired
    private DevListingSeeder seeder;

    // --- DoD 1: a `live` listing → S12 subscribe succeeds -------------------------------------------

    @Test
    void live_stage_unblocks_subscribe() throws Exception {
        Map<String, Object> seeded = seeder.seed("live", null, null, null);
        UUID listingId = uuid(seeded, "listing_id");
        assertThat(seeded.get("status")).isEqualTo("live");
        assertThat(seeded.get("subscription_id")).isNull();

        subscribe(bearerFor("ops@dev.local"), listingId, str(seeded, "investor_id"), 1_000_000L)
                .andExpect(status().isCreated());

        assertThat(listingStatus(listingId)).isEqualTo("live");
        assertThat(jdbc.queryForObject("SELECT committed_total FROM deal_listing WHERE listing_id = ?",
                Long.class, listingId)).isEqualTo(1_000_000L);
    }

    // --- DoD 2: a `disbursable` listing → S6 approve disburses; same-maker approve is rejected -------

    @Test
    void disbursable_stage_unblocks_approve_by_a_different_treasury() throws Exception {
        Map<String, Object> seeded = seeder.seed("disbursable", null, null, "treasury@dev.local");
        UUID listingId = uuid(seeded, "listing_id");
        assertThat(seeded.get("status")).isEqualTo("fully_funded");
        assertThat(payoutStatus(listingId, "disbursement")).isEqualTo("drafted");

        approveDisbursement(bearerFor("treasury2@dev.local"), listingId).andExpect(status().isOk());

        assertThat(listingStatus(listingId)).isEqualTo("disbursed");
        assertThat(payoutStatus(listingId, "disbursement")).isEqualTo("executed");
    }

    @Test
    void disbursable_stage_rejects_a_same_maker_approve() throws Exception { // checker ≠ maker still holds
        Map<String, Object> seeded = seeder.seed("disbursable", null, null, "treasury@dev.local");
        UUID listingId = uuid(seeded, "listing_id");

        approveDisbursement(bearerFor("treasury@dev.local"), listingId) // maker == approver
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("checker_equals_maker"));

        assertThat(listingStatus(listingId)).isEqualTo("fully_funded"); // not disbursed
    }

    // --- DoD 3: `fully_funded` → draft; `disbursed` → record-maturity -------------------------------

    @Test
    void fully_funded_stage_unblocks_disbursement_draft() throws Exception {
        Map<String, Object> seeded = seeder.seed("fully_funded", null, null, null);
        UUID listingId = uuid(seeded, "listing_id");
        assertThat(seeded.get("status")).isEqualTo("fully_funded");
        assertThat(seeded.get("subscription_id")).isNotNull();

        mvc.perform(envelope(post("/listings/{id}/disbursement/draft", listingId), bearerFor("treasury@dev.local")))
                .andExpect(status().isCreated());
        assertThat(payoutStatus(listingId, "disbursement")).isEqualTo("drafted");
    }

    @Test
    void disbursed_stage_unblocks_record_maturity() throws Exception {
        Map<String, Object> seeded = seeder.seed("disbursed", null, null, null);
        UUID listingId = uuid(seeded, "listing_id");
        assertThat(seeded.get("status")).isEqualTo("disbursed");
        long faceValue = jdbc.queryForObject("SELECT i.face_value FROM deal_invoice i "
                + "JOIN deal_listing l ON l.invoice_id = i.invoice_id WHERE l.listing_id = ?", Long.class, listingId);

        mvc.perform(envelope(post("/listings/{id}/record-maturity", listingId), bearerFor("ops@dev.local"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("amount_paise", faceValue, "utr", "DEV-UTR"))))
                .andExpect(status().isOk());
        assertThat(listingStatus(listingId)).isEqualTo("matured_payment_received");
    }

    // --- DoD 3/S13: `matured` → distribution approve closes the deal --------------------------------

    @Test
    void matured_stage_unblocks_distribution_approve() throws Exception {
        Map<String, Object> seeded = seeder.seed("matured", null, null, "treasury@dev.local");
        UUID listingId = uuid(seeded, "listing_id");
        assertThat(seeded.get("status")).isEqualTo("matured_payment_received");
        assertThat(payoutStatus(listingId, "distribution")).isEqualTo("drafted");

        mvc.perform(envelope(post("/listings/{id}/distribution/approve", listingId), bearerFor("treasury2@dev.local")))
                .andExpect(status().isOk());

        assertThat(listingStatus(listingId)).isEqualTo("closed");
        assertThat(jdbc.queryForObject("SELECT terminal_outcome::text FROM deal_listing WHERE listing_id = ?",
                String.class, listingId)).isEqualTo("distributed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tax_tds_deduction WHERE listing_id = ?",
                Integer.class, listingId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status::text FROM sub_subscription WHERE listing_id = ?",
                String.class, listingId)).isEqualTo("distribution_received");
    }

    // --- the seeder mints fresh, non-colliding ids across calls -------------------------------------

    @Test
    void repeated_calls_mint_distinct_listings() {
        UUID first = uuid(seeder.seed("live", null, null, null), "listing_id");
        UUID second = uuid(seeder.seed("live", null, null, null), "listing_id");
        assertThat(first).isNotEqualTo(second);
    }

    // --- helpers -----------------------------------------------------------------------------------

    private ResultActions subscribe(String bearer, UUID listingId, String investorId, long amountPaise)
            throws Exception {
        return mvc.perform(envelope(post("/listings/{id}/subscriptions/commit", listingId), bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("investor_id", investorId, "amount_paise", amountPaise))));
    }

    private ResultActions approveDisbursement(String bearer, UUID listingId) throws Exception {
        return mvc.perform(envelope(post("/listings/{id}/disbursement/approve", listingId), bearer));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder envelope(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String bearer) {
        return builder
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", UUID.randomUUID().toString());
    }

    /** A session bearer for a {@link DevDataSeeder}-seeded admin, logged in over the real password → OTP flow. */
    private String bearerFor(String email) {
        return bearerForCredentials(email, DevDataSeeder.PASSWORD);
    }

    private String listingStatus(UUID listingId) {
        return jdbc.queryForObject("SELECT status::text FROM deal_listing WHERE listing_id = ?", String.class, listingId);
    }

    private String payoutStatus(UUID listingId, String kind) {
        return jdbc.queryForObject("SELECT status::text FROM cash_payout_instruction WHERE listing_id = ? "
                + "AND kind = ?::cash_payout_kind", String.class, listingId, kind);
    }

    private static UUID uuid(Map<String, Object> m, String key) {
        return UUID.fromString(str(m, key));
    }

    private static String str(Map<String, Object> m, String key) {
        return (String) m.get(key);
    }
}
