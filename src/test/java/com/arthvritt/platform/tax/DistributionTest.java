package com.arthvritt.platform.tax;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M16 distribution — the final money-lifecycle gate (DL-BE-066). On a {@code matured_payment_received}
 * listing, Treasury drafts (maker) the per-investor TDS split and a different Treasury checker approves
 * (≠ maker, fresh MFA); every investor is paid their net, the {@code tax_tds_deduction} ledger is written,
 * FY cumulatives bump, and the deal closes as {@code distributed}. TDS is on the return only, 10% with a PAN
 * and 20% without (206AA), and {@code Σ gross = face_value} to the paise.
 *
 * <p>Fixtures: two investors fund a ₹5,00,000 target (A ₹3,00,000 with PAN, B ₹2,00,000 no PAN); the buyer
 * repays a ₹5,10,000 face → ₹10,000 total return. A's return = ₹6,000 (30/50), B's = ₹4,000. TDS: A ₹600
 * (10%), B ₹800 (20%) → total ₹1,400. Nets: A 30,60,000−600 = 30,54,000 paise; B 20,40,000−800 = 20,32,000.
 */
class DistributionTest extends AbstractEdgeHttpTest {

    private static final long FACE = 51_000_000L;           // ₹5,10,000 buyer repayment
    private static final long FUNDING_TARGET = 50_000_000L;  // ₹5,00,000 funded
    private static final long PRINCIPAL_A = 30_000_000L;     // investor A (PAN)
    private static final long PRINCIPAL_B = 20_000_000L;     // investor B (no PAN → 206AA)

    private String maker;
    private String checker;
    private UUID adminUserId;
    private UUID listingId;
    private UUID investorA;
    private UUID investorB;

    @BeforeEach
    void seed() {
        notifier.clear();
        Seeded makerAdmin = seedAdminWithRoles("treasury_and_settlement");
        maker = bearerFor(makerAdmin);
        adminUserId = makerAdmin.adminUserId();
        checker = bearerFor(seedAdminWithRoles("treasury_and_settlement"));
        investorA = seedActiveInvestor("ABCDE1234F"); // has a verified PAN → 10%
        investorB = seedActiveInvestor(null);         // no PAN → 20% (206AA)
        listingId = seedMaturedListing();
        seedConfirmedSubscription(investorA, PRINCIPAL_A);
        seedConfirmedSubscription(investorB, PRINCIPAL_B);
    }

    @Test
    void draft_then_approve_distributes_and_closes_the_deal() throws Exception {
        draft(maker).andExpect(status().isCreated());
        assertThat(distributionStatus()).isEqualTo("drafted");
        assertThat(listingStatus()).isEqualTo("matured_payment_received");

        approve(checker).andExpect(status().isOk());

        assertThat(distributionStatus()).isEqualTo("executed");
        assertThat(listingStatus()).isEqualTo("closed");
        assertThat(terminalOutcome()).isEqualTo("distributed");

        // Per-investor TDS ledger: A 10% of ₹6,000 = ₹600; B 20% of ₹4,000 = ₹800.
        assertThat(tds(investorA)).isEqualTo(60_000L);
        assertThat(tds(investorB)).isEqualTo(80_000L);
        assertThat(net(investorA)).isEqualTo(30_540_000L);
        assertThat(net(investorB)).isEqualTo(20_320_000L);
        // Σ gross = face_value (no paise created or lost).
        assertThat(jdbc.queryForObject("SELECT sum(gross_paise) FROM tax_tds_deduction WHERE listing_id = ?",
                Long.class, listingId)).isEqualTo(FACE);
        // total_tds recorded on the instruction.
        assertThat(jdbc.queryForObject("SELECT total_tds_amount FROM cash_payout_instruction WHERE listing_id = ?",
                Long.class, listingId)).isEqualTo(140_000L);

        // FY cumulatives track taxable income (interest) + TDS, and the rate is stamped + frozen.
        assertThat(rateBps(investorA)).isEqualTo(1000);
        assertThat(rateBps(investorB)).isEqualTo(2000);
        assertThat(cumulativeTds(investorA)).isEqualTo(60_000L);
        assertThat(cumulativeGrossIncome(investorA)).isEqualTo(6_000_00L); // ₹6,000 interest

        // Each subscription carries its distribution_outcome and advanced to distribution_received.
        assertThat(subStatus(investorA)).isEqualTo("distribution_received");
        assertThat(subStatus(investorB)).isEqualTo("distribution_received");

        // The deal-close envelope is chained.
        assertThat(envelopes("listing.Listing.Distributed")).isEqualTo(1);
    }

    @Test
    void the_maker_cannot_approve_the_distribution() throws Exception { // maker ≠ checker (C4/PI.5)
        String dual = bearerFor(seedAdminWithRoles("treasury_and_settlement"));
        draft(dual).andExpect(status().isCreated());
        approve(dual)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("checker_equals_maker"));
        assertThat(listingStatus()).isEqualTo("matured_payment_received"); // not closed
    }

    @Test
    void approve_with_a_stale_mfa_assertion_is_401() throws Exception {
        draft(maker).andExpect(status().isCreated());
        jdbc.update("UPDATE auth_otp_challenge SET consumed_at = now() - interval '10 minutes' "
                        + "WHERE assertion_id = (SELECT mfa_assertion_id FROM auth_session WHERE session_id = ?)",
                UUID.fromString(checker));
        approve(checker)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error_code").value("mfa_assertion_expired"));
        assertThat(listingStatus()).isEqualTo("matured_payment_received");
    }

    @Test
    void draft_on_a_non_matured_listing_is_rejected() throws Exception { // DIS.1
        jdbc.update("UPDATE deal_listing SET status = 'disbursed' WHERE listing_id = ?", listingId);
        draft(maker).andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cash_payout_instruction WHERE listing_id = ?",
                Integer.class, listingId)).isZero();
    }

    @Test
    void a_second_distribution_draft_for_the_same_listing_is_rejected() throws Exception {
        draft(maker).andExpect(status().isCreated());
        draft(checker).andExpect(status().is4xxClientError()); // different command_id, same listing
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cash_payout_instruction WHERE listing_id = ?",
                Integer.class, listingId)).isEqualTo(1);
    }

    @Test
    void draft_by_a_non_treasury_actor_is_403() throws Exception { // SoD
        String ops = bearerFor(seedAdminWithRoles("ops_executive"));
        draft(ops)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));
    }

    @Test
    void approve_is_idempotent_on_command_id() throws Exception {
        draft(maker).andExpect(status().isCreated());
        UUID commandId = UUID.randomUUID();
        mvc.perform(approveBuilder(checker, commandId)).andExpect(status().isOk());
        mvc.perform(approveBuilder(checker, commandId)).andExpect(status().isOk()); // replay → no double-pay

        assertThat(listingStatus()).isEqualTo("closed");
        assertThat(envelopes("listing.Listing.Distributed")).isEqualTo(1); // counted once
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tax_tds_deduction WHERE listing_id = ?",
                Integer.class, listingId)).isEqualTo(2); // one row per investor, not four
    }

    // --- helpers -----------------------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions draft(String bearer) throws Exception {
        return mvc.perform(envelope(post("/listings/{id}/distribution/draft", listingId), bearer));
    }

    private org.springframework.test.web.servlet.ResultActions approve(String bearer) throws Exception {
        return mvc.perform(envelope(post("/listings/{id}/distribution/approve", listingId), bearer));
    }

    private MockHttpServletRequestBuilder approveBuilder(String bearer, UUID commandId) {
        return post("/listings/{id}/distribution/approve", listingId)
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", commandId.toString());
    }

    private MockHttpServletRequestBuilder envelope(MockHttpServletRequestBuilder builder, String bearer) {
        return builder
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", UUID.randomUUID().toString());
    }

    private String distributionStatus() {
        return jdbc.queryForObject("SELECT status::text FROM cash_payout_instruction "
                + "WHERE listing_id = ? AND kind = 'distribution'", String.class, listingId);
    }

    private String listingStatus() {
        return jdbc.queryForObject("SELECT status::text FROM deal_listing WHERE listing_id = ?", String.class, listingId);
    }

    private String terminalOutcome() {
        return jdbc.queryForObject("SELECT terminal_outcome::text FROM deal_listing WHERE listing_id = ?",
                String.class, listingId);
    }

    private long tds(UUID investor) {
        return jdbc.queryForObject("SELECT tds_amount_paise FROM tax_tds_deduction "
                + "WHERE listing_id = ? AND investor_id = ?", Long.class, listingId, investor);
    }

    private long net(UUID investor) {
        return jdbc.queryForObject("SELECT net_paise FROM tax_tds_deduction "
                + "WHERE listing_id = ? AND investor_id = ?", Long.class, listingId, investor);
    }

    private int rateBps(UUID investor) {
        return jdbc.queryForObject("SELECT tds_rate_bps FROM tax_year_profile WHERE investor_id = ?",
                Integer.class, investor);
    }

    private long cumulativeTds(UUID investor) {
        return jdbc.queryForObject("SELECT cumulative_tds_paise FROM tax_year_profile WHERE investor_id = ?",
                Long.class, investor);
    }

    private long cumulativeGrossIncome(UUID investor) {
        return jdbc.queryForObject("SELECT cumulative_gross_paise FROM tax_year_profile WHERE investor_id = ?",
                Long.class, investor);
    }

    private String subStatus(UUID investor) {
        return jdbc.queryForObject("SELECT status::text FROM sub_subscription "
                + "WHERE listing_id = ? AND investor_id = ?", String.class, listingId, investor);
    }

    private int envelopes(String eventType) {
        return jdbc.queryForObject("SELECT count(*) FROM sys_audit_event WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, eventType, listingId);
    }

    private UUID seedMaturedListing() {
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
                        + "VALUES (?, ?, ?, ?, 'matured_payment_received', ?, ?, TRUE)",
                listing, invoiceId, supplierId, buyerId, FUNDING_TARGET, FUNDING_TARGET);
        return listing;
    }

    private void seedConfirmedSubscription(UUID investor, long amount) {
        jdbc.update("INSERT INTO sub_subscription (subscription_id, listing_id, investor_id, amount, status, "
                        + "expected_inflow_amount) VALUES (?, ?, ?, ?, 'confirmed', ?)",
                UUID.randomUUID(), listingId, investor, amount, amount);
    }

    private UUID seedActiveInvestor(String pan) {
        UUID identityId = UUID.randomUUID();
        UUID inviteId = UUID.randomUUID();
        UUID investor = UUID.randomUUID();
        String email = "inv-" + investor + "@arthvritt.test";
        jdbc.update("INSERT INTO auth_identity (identity_id, kind, email, phone_e164, display_name, status) "
                        + "VALUES (?, 'investor'::identity_kind_enum, ?, ?, 'Investor', 'active'::identity_status_enum)",
                identityId, email, phone());
        jdbc.update("INSERT INTO inv_invite (invite_id, email_hash, phone_hash, issued_by, expiry_at, status) "
                        + "VALUES (?, ?, ?, ?, now() + interval '14 days', 'pending')",
                inviteId, email.getBytes(StandardCharsets.UTF_8), "p".getBytes(StandardCharsets.UTF_8), adminUserId);
        jdbc.update("INSERT INTO inv_account (investor_id, identity_id, invite_id, sub_type, status, pan) "
                        + "VALUES (?, ?, ?, 'resident_individual'::inv_sub_type, 'active'::inv_account_status, ?)",
                investor, identityId, inviteId, pan);
        return investor;
    }
}
