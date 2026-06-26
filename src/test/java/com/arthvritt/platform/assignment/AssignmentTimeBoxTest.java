package com.arthvritt.platform.assignment;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M12-B (DL-BE-053) — the G13 24h time-box (AS.4) and the per-leg failure/retry path (AS.5/SR.2). Past the
 * deadline with an unsigned leg, the set is declared {@code incomplete} and the listing held for review (the
 * C27 gate never opens); a failed leg blocks {@code all_signed} until it is re-initiated and signed.
 */
class AssignmentTimeBoxTest extends AbstractEdgeHttpTest {

    private static final long LEG = 25_000_000L;
    private static final long FUNDING_TARGET = 50_000_000L;

    private String ops;
    private UUID adminUserId;
    private UUID listingId;
    private UUID investorA;
    private UUID investorB;

    @BeforeEach
    void seed() {
        notifier.clear();
        Seeded opsAdmin = seedAdminWithRoles("ops_executive");
        ops = bearerFor(opsAdmin);
        adminUserId = opsAdmin.adminUserId();
        investorA = seedActiveInvestor();
        investorB = seedActiveInvestor();
        seedFullyFundedListing();
        seedConfirmedSubscription(investorA, LEG);
        seedConfirmedSubscription(investorB, LEG);
    }

    @Test
    void declaring_incomplete_after_the_window_holds_the_listing_for_review() throws Exception {
        requestSet();
        completeSigning(investorA); // one of two legs signed
        forceDeadlinePast();

        send(post("/listings/{id}/assignment-set/declare-incomplete", listingId), Map.of());

        assertThat(setStatus()).isEqualTo("incomplete");
        assertThat(listingStatus()).isEqualTo("held_for_review");
        assertThat(listingAllSigned()).isFalse(); // C27 gate never opened
        assertThat(envelopes("listing.Listing.HeldForReview")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void declaring_incomplete_before_the_window_closes_is_rejected() throws Exception {
        requestSet();
        completeSigning(investorA);
        mvc.perform(withEnvelope(post("/listings/{id}/assignment-set/declare-incomplete", listingId), ops, Map.of()))
                .andExpect(status().is4xxClientError());
        assertThat(setStatus()).isEqualTo("in_progress");
    }

    @Test
    void signing_a_leg_past_the_deadline_is_rejected() throws Exception { // AS.4
        requestSet();
        forceDeadlinePast();
        mvc.perform(withEnvelope(post("/listings/{id}/assignment-set/complete-signing", listingId), ops,
                        Map.of("investor_id", investorA.toString())))
                .andExpect(status().is4xxClientError());
        assertThat(signedCount()).isZero();
    }

    @Test
    void a_failed_leg_blocks_all_signed_until_it_is_resigned() throws Exception { // AS.5 / SR.2
        requestSet();
        send(post("/listings/{id}/assignment-set/record-leg-failed", listingId),
                Map.of("investor_id", investorA.toString(), "reason", "OTP timeout"));
        completeSigning(investorB);
        assertThat(signedCount()).isEqualTo(1);
        assertThat(setStatus()).isEqualTo("in_progress"); // A still unsigned → gate closed
        assertThat(listingAllSigned()).isFalse();

        // Retry A's signature (fresh MIA/request, retry_count=1), then complete it → all_signed.
        send(post("/listings/{id}/assignment-set/reinitiate-leg", listingId),
                Map.of("investor_id", investorA.toString()));
        completeSigning(investorA);

        assertThat(signedCount()).isEqualTo(2);
        assertThat(setStatus()).isEqualTo("all_signed");
        assertThat(listingAllSigned()).isTrue();
    }

    // --- helpers -----------------------------------------------------------------------------------

    private void forceDeadlinePast() {
        jdbc.update("UPDATE legal_assignment_set SET sign_deadline = now() - interval '1 hour' WHERE listing_id = ?",
                listingId);
    }

    private void requestSet() throws Exception {
        mvc.perform(post("/listings/{id}/assignment-set/request", listingId)
                        .header("Authorization", "Bearer " + ops)
                        .header("X-Command-Id", UUID.randomUUID().toString()))
                .andExpect(status().isCreated());
    }

    private void completeSigning(UUID investor) throws Exception {
        send(post("/listings/{id}/assignment-set/complete-signing", listingId),
                Map.of("investor_id", investor.toString()));
    }

    private void send(MockHttpServletRequestBuilder builder, Map<String, Object> body) throws Exception {
        mvc.perform(withEnvelope(builder, ops, body)).andExpect(status().is2xxSuccessful());
    }

    private MockHttpServletRequestBuilder withEnvelope(MockHttpServletRequestBuilder builder, String bearer,
                                                       Map<String, Object> body) throws Exception {
        return builder
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
    }

    private String setStatus() {
        return jdbc.queryForObject("SELECT status::text FROM legal_assignment_set WHERE listing_id = ?",
                String.class, listingId);
    }

    private String listingStatus() {
        return jdbc.queryForObject("SELECT status::text FROM deal_listing WHERE listing_id = ?", String.class, listingId);
    }

    private int signedCount() {
        return jdbc.queryForObject("SELECT signed_count FROM legal_assignment_set WHERE listing_id = ?",
                Integer.class, listingId);
    }

    private boolean listingAllSigned() {
        return jdbc.queryForObject("SELECT all_signed FROM deal_listing WHERE listing_id = ?",
                Boolean.class, listingId);
    }

    private int envelopes(String eventType) {
        return jdbc.queryForObject("SELECT count(*) FROM sys_audit_event WHERE event_type = ?", Integer.class, eventType);
    }

    private void seedFullyFundedListing() {
        listingId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        jdbc.update("INSERT INTO deal_invoice (invoice_id, supplier_id, buyer_id, invoice_number, face_value, "
                        + "invoice_date, tenor_days, due_date, status) "
                        + "VALUES (?, ?, ?, ?, ?, '2026-06-01', 60, '2026-07-31', 'listed')",
                invoiceId, supplierId, buyerId, "INV-" + invoiceId, 60_000_000L);
        jdbc.update("INSERT INTO deal_listing (listing_id, invoice_id, supplier_id, buyer_id, status, "
                        + "funding_target, committed_total, all_signed) "
                        + "VALUES (?, ?, ?, ?, 'fully_funded', ?, ?, FALSE)",
                listingId, invoiceId, supplierId, buyerId, FUNDING_TARGET, FUNDING_TARGET);
    }

    private void seedConfirmedSubscription(UUID investor, long amount) {
        jdbc.update("INSERT INTO sub_subscription (subscription_id, listing_id, investor_id, amount, status, "
                        + "expected_inflow_amount) VALUES (?, ?, ?, ?, 'confirmed', ?)",
                UUID.randomUUID(), listingId, investor, amount, amount);
    }

    private UUID seedActiveInvestor() {
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
        jdbc.update("INSERT INTO inv_account (investor_id, identity_id, invite_id, sub_type, status) "
                        + "VALUES (?, ?, ?, 'resident_individual'::inv_sub_type, 'active'::inv_account_status)",
                investor, identityId, inviteId);
        return investor;
    }
}
