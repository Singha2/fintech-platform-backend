package com.arthvritt.platform.listing;

import com.arthvritt.platform.shared.BusinessDate;
import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WS-4 listing → go-live (see docs/modules/WS-4-listing-golive.md §7): the first money-flow + maker-checker
 * gate. Drives one invoice create → ops-checks → pricing snapshot → go-live → VA over HTTP, asserting the
 * frozen {@code funding_target} (HALF_EVEN, exact paise), snapshot immutability, the two-endpoint
 * maker-checker (checker≠maker), MFA on the checker, and SoD. Seeds active supplier/buyer/pricing-band
 * directly (upstream context state; the full onboard-over-HTTP E2E is the WS-7 capstone).
 */
class ListingGoLiveTest extends AbstractEdgeHttpTest {

    // Worked HALF_EVEN example (asserted exactly):
    //   face=100000000, rate=1200bps, tenor=60d, fee=200bps
    //   discount = 100000000*1200/10000*60/365 = 1972602.74 → 1972603
    //   fee      = 100000000*200/10000          = 2000000
    //   funding_target = 100000000 − 1972603 − 2000000 = 96027397
    private static final long FACE = 100_000_000L;
    private static final int RATE_BPS = 1200;
    private static final int TENOR_DAYS = 60;
    private static final long EXPECTED_FUNDING_TARGET = 96_027_397L;

    private String ops;
    private String treasury;
    private UUID supplierId;
    private UUID buyerId;

    @BeforeEach
    void seed() {
        notifier.clear();
        AbstractEdgeHttpTest.Seeded opsAdmin = seedAdminWithRoles("ops_executive");
        ops = bearerFor(opsAdmin);
        treasury = bearerFor(seedAdminWithRoles("treasury_and_settlement"));
        supplierId = seedActiveSupplier();
        buyerId = seedActiveBuyer(opsAdmin.adminUserId());
        seedPricingBand(buyerId, "31_60d", 1000, 1500, 200); // rate band covers 1200; fee 200bps
    }

    @Test
    void happy_path_lists_prices_and_goes_live_creating_the_va() throws Exception {
        UUID listing = createListing();
        passAllOpsChecks(listing, ops);
        assertThat(statusOf(listing)).isEqualTo("awaiting_acknowledgment");

        send(post("/listings/{id}/snapshot-and-ready", listing), ops, listing, Map.of("rate_bps", RATE_BPS));
        assertThat(statusOf(listing)).isEqualTo("ready_for_review");
        assertThat(fundingTargetOf(listing)).isEqualTo(EXPECTED_FUNDING_TARGET);

        send(post("/listings/{id}/approve-go-live", listing), treasury, listing, Map.of());
        assertThat(statusOf(listing)).isEqualTo("live");

        // VA created, one per listing, expected inflow = funding_target, va_id set on the listing.
        UUID vaId = jdbc.queryForObject("SELECT va_id FROM deal_listing WHERE listing_id = ?", UUID.class, listing);
        assertThat(vaId).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status::text FROM cash_virtual_account WHERE va_id = ?",
                String.class, vaId)).isEqualTo("created");
        assertThat(jdbc.queryForObject("SELECT expected_inflow_total FROM cash_virtual_account WHERE va_id = ?",
                Long.class, vaId)).isEqualTo(EXPECTED_FUNDING_TARGET);
    }

    @Test
    void funding_target_is_frozen_against_a_later_band_change() throws Exception { // L.3/G20
        UUID listing = readyForReview();
        long frozen = fundingTargetOf(listing);
        // Supersede the band with a wildly different rate; the frozen listing must not move.
        supersedeBand(buyerId, "31_60d", 9000, 9500, 900);
        assertThat(fundingTargetOf(listing)).isEqualTo(frozen);
    }

    @Test
    void the_maker_cannot_approve_go_live() throws Exception { // maker ≠ checker (C4)
        String dual = bearerFor(seedAdminWithRoles("ops_executive", "treasury_and_settlement"));
        UUID listing = createListing(dual);
        passAllOpsChecks(listing, dual);
        send(post("/listings/{id}/snapshot-and-ready", listing), dual, listing, Map.of("rate_bps", RATE_BPS));

        mvc.perform(withEnvelope(post("/listings/{id}/approve-go-live", listing), dual, listing, Map.of()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("checker_equals_maker"));
        assertThat(statusOf(listing)).isEqualTo("ready_for_review"); // not live
    }

    @Test
    void approve_go_live_with_a_stale_mfa_assertion_is_401() throws Exception {
        UUID listing = readyForReview();
        jdbc.update("UPDATE auth_otp_challenge SET consumed_at = now() - interval '10 minutes' "
                + "WHERE assertion_id = (SELECT mfa_assertion_id FROM auth_session WHERE session_id = ?)",
                UUID.fromString(treasury));
        mvc.perform(withEnvelope(post("/listings/{id}/approve-go-live", listing), treasury, listing, Map.of()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error_code").value("mfa_assertion_expired"));
        assertThat(statusOf(listing)).isEqualTo("ready_for_review");
    }

    @Test
    void go_live_holds_for_review_when_the_buyer_is_suspended() throws Exception { // L.11
        UUID listing = readyForReview();
        jdbc.update("UPDATE buyer_account SET status = 'suspended' WHERE buyer_id = ?", buyerId);

        send(post("/listings/{id}/approve-go-live", listing), treasury, listing, Map.of());

        assertThat(statusOf(listing)).isEqualTo("held_for_review");
        // No virtual account and no funding window were created on the hold path.
        assertThat(jdbc.queryForObject("SELECT va_id FROM deal_listing WHERE listing_id = ?", UUID.class, listing))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cash_virtual_account WHERE listing_id = ?",
                Integer.class, listing)).isZero();
    }

    @Test
    void go_live_holds_for_review_when_the_supplier_is_suspended() throws Exception { // L.11
        UUID listing = readyForReview();
        jdbc.update("UPDATE sup_account SET status = 'suspended' WHERE supplier_id = ?", supplierId);

        send(post("/listings/{id}/approve-go-live", listing), treasury, listing, Map.of());

        assertThat(statusOf(listing)).isEqualTo("held_for_review");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cash_virtual_account WHERE listing_id = ?",
                Integer.class, listing)).isZero();
    }

    @Test
    void approve_go_live_by_a_non_treasury_actor_is_403() throws Exception { // SoD
        UUID listing = readyForReview();
        mvc.perform(withEnvelope(post("/listings/{id}/approve-go-live", listing), ops, listing, Map.of()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));
    }

    @Test
    void snapshot_and_ready_by_a_non_ops_actor_is_403() throws Exception { // SoD
        UUID listing = createListing();
        passAllOpsChecks(listing, ops);
        mvc.perform(withEnvelope(post("/listings/{id}/snapshot-and-ready", listing), treasury, listing,
                        Map.of("rate_bps", RATE_BPS)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));
    }

    @Test
    void a_rate_outside_the_band_is_rejected() throws Exception { // L.10
        UUID listing = createListing();
        passAllOpsChecks(listing, ops);
        mvc.perform(withEnvelope(post("/listings/{id}/snapshot-and-ready", listing), ops, listing,
                        Map.of("rate_bps", 5000))) // band is [1000,1500]
                .andExpect(status().isBadRequest());
        assertThat(statusOf(listing)).isEqualTo("awaiting_acknowledgment");
    }

    @Test
    void an_overflowing_rate_bps_is_rejected_at_the_edge_not_wrapped() throws Exception {
        UUID listing = createListing();
        passAllOpsChecks(listing, ops);
        // 4294968496 wrapped via (int) would have been 1200 (a valid in-band rate); the edge must reject it.
        mvc.perform(withEnvelope(post("/listings/{id}/snapshot-and-ready", listing), ops, listing,
                        Map.of("rate_bps", 4_294_968_496L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("validation_failed"));
        assertThat(statusOf(listing)).isEqualTo("awaiting_acknowledgment");
    }

    @Test
    void an_absurd_rate_making_funding_target_non_positive_is_a_clean_400() throws Exception {
        UUID listing = createListing();
        passAllOpsChecks(listing, ops);
        // Widen the band to admit a 1000% rate; on a 60-day tenor the discount exceeds face → target ≤ 0.
        supersedeBand(buyerId, "31_60d", 1000, 100000, 200);
        mvc.perform(withEnvelope(post("/listings/{id}/snapshot-and-ready", listing), ops, listing,
                        Map.of("rate_bps", 100000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("validation_failed"));
        assertThat(statusOf(listing)).isEqualTo("awaiting_acknowledgment");
    }

    @Test
    void funding_window_closes_five_business_days_after_go_live() throws Exception { // L.8 / DL-BE-040
        // Weekend-only kernel (no holidays configured in tests, so this matches the app's calendar exactly).
        var kernel = new BusinessDate(d -> false);
        var today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        var expected = kernel.plusBusinessDays(today, 5);

        UUID listing = createListing();
        passAllOpsChecks(listing, ops);
        send(post("/listings/{id}/snapshot-and-ready", listing), ops, listing, Map.of("rate_bps", RATE_BPS));
        send(post("/listings/{id}/approve-go-live", listing), treasury, listing, Map.of());

        OffsetDateTime closeAt = jdbc.queryForObject(
                "SELECT funding_window_close_at FROM deal_listing WHERE listing_id = ?",
                OffsetDateTime.class, listing);
        LocalDate closeDate = closeAt.atZoneSameInstant(ZoneId.of("Asia/Kolkata")).toLocalDate();

        assertThat(closeDate).isEqualTo(expected);
        assertThat(closeDate.getDayOfWeek())
                .isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    }

    @Test
    void get_listing_returns_the_aggregate_read() throws Exception {
        UUID listing = createListing();
        mvc.perform(get("/listings/{id}", listing).header("Authorization", "Bearer " + ops))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listing_id").value(listing.toString()))
                .andExpect(jsonPath("$.status").value("draft"));
    }

    // --- helpers -----------------------------------------------------------------------------------

    /**
     * The full M9-C ops-check flow (start → record all 7 → complete) plus the M9-D admin-captured buyer
     * acknowledgment → listing 'awaiting_acknowledgment' with the ack recorded (snapshot-ready).
     */
    private void passAllOpsChecks(UUID listing, String bearer) throws Exception {
        send(post("/listings/{id}/start-ops-checks", listing), bearer, listing, Map.of());
        send(post("/listings/{id}/record-ops-check", listing), bearer, listing, Map.of("check_name", "irn_validity"));
        for (String check : new String[]{"eway_bill_match", "buyer_supplier_relationship", "duplicate_check",
                "supplier_exposure_cap", "buyer_limit_headroom", "document_completeness"}) {
            send(post("/listings/{id}/record-ops-check", listing), bearer, listing,
                    Map.of("check_name", check, "outcome", "passed"));
        }
        send(post("/listings/{id}/complete-ops-checks", listing), bearer, listing, Map.of());
        send(post("/listings/{id}/record-buyer-ack", listing), bearer, listing,
                Map.of("outcome", "acknowledged", "method", "email"));
    }

    private UUID readyForReview() throws Exception {
        UUID listing = createListing();
        passAllOpsChecks(listing, ops);
        send(post("/listings/{id}/snapshot-and-ready", listing), ops, listing, Map.of("rate_bps", RATE_BPS));
        return listing;
    }

    private UUID createListing() throws Exception {
        return createListing(ops);
    }

    private UUID createListing(String bearer) throws Exception {
        MvcResult res = mvc.perform(post("/listings")
                        .header("Authorization", "Bearer " + bearer)
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

    private String statusOf(UUID listing) {
        return jdbc.queryForObject("SELECT status::text FROM deal_listing WHERE listing_id = ?", String.class, listing);
    }

    private long fundingTargetOf(UUID listing) {
        return jdbc.queryForObject("SELECT funding_target FROM deal_listing WHERE listing_id = ?", Long.class, listing);
    }

    private int versionOf(UUID listing) {
        return jdbc.queryForObject("SELECT aggregate_version FROM deal_listing WHERE listing_id = ?",
                Integer.class, listing);
    }

    // --- seeding (upstream context state) ----------------------------------------------------------

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

    private void seedPricingBand(UUID buyer, String bucket, int min, int max, int fee) {
        jdbc.update("INSERT INTO risk_pricing_policy (pricing_band_id, buyer_id, tenor_bucket, "
                        + "rate_range_min_bps, rate_range_max_bps, fee_bps, effective_from) "
                        + "VALUES (?, ?, ?::risk_tenor_bucket, ?, ?, ?, now()::date)",
                UUID.randomUUID(), buyer, bucket, min, max, fee);
    }

    private void supersedeBand(UUID buyer, String bucket, int min, int max, int fee) {
        // The test only needs the ACTIVE band to change after ready_for_review (to prove the snapshot is
        // frozen). superseded_by is a self-FK, so rather than build a supersession chain, swap the active
        // band: drop the current one (nothing references it for a fresh test buyer) and seed a new one.
        jdbc.update("DELETE FROM risk_pricing_policy WHERE buyer_id = ? AND tenor_bucket = ?::risk_tenor_bucket "
                + "AND superseded_by IS NULL", buyer, bucket);
        seedPricingBand(buyer, bucket, min, max, fee);
    }
}
