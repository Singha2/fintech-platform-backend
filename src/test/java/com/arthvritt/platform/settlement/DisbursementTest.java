package com.arthvritt.platform.settlement;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WS-7 disbursement (see docs/modules/WS-7-disbursement.md §7): the second maker-checker+MFA gate. On a
 * fully_funded + all_signed listing, Treasury drafts (maker) and a different Treasury checker approves
 * (≠ maker, fresh MFA), the escrow payout executes, and the listing flips to {@code disbursed}.
 */
class DisbursementTest extends AbstractEdgeHttpTest {

    private static final long FUNDING_TARGET = 50_000_000L;

    private String maker;   // treasury_and_settlement
    private String checker; // treasury_and_settlement (≠ maker)
    private UUID listingId;

    @BeforeEach
    void seed() {
        notifier.clear();
        maker = bearerFor(seedAdminWithRoles("treasury_and_settlement"));
        checker = bearerFor(seedAdminWithRoles("treasury_and_settlement"));
        listingId = seedListing(true); // fully_funded + all_signed
    }

    @Test
    void draft_then_approve_disburses_the_listing() throws Exception {
        draft(maker, listingId).andExpect(status().isCreated());
        assertThat(payoutStatus()).isEqualTo("drafted");
        assertThat(listingStatus()).isEqualTo("fully_funded");

        approve(checker, listingId).andExpect(status().isOk());
        assertThat(payoutStatus()).isEqualTo("executed");
        assertThat(listingStatus()).isEqualTo("disbursed");
        // The escrow leg produced a UTR (the M5b ACL envelope).
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_event "
                + "WHERE event_type = 'banking.PayoutLegWebhookProcessed'", Integer.class)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void draft_before_all_signed_is_rejected() throws Exception { // PI.2 gate
        UUID notSigned = seedListing(false); // fully_funded but all_signed=false
        draft(maker, notSigned).andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cash_payout_instruction WHERE listing_id = ?",
                Integer.class, notSigned)).isZero();
    }

    @Test
    void the_maker_cannot_approve_the_payout() throws Exception { // maker ≠ checker (C4/PI.5)
        String dual = bearerFor(seedAdminWithRoles("treasury_and_settlement"));
        draft(dual, listingId).andExpect(status().isCreated());
        approve(dual, listingId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("checker_equals_maker"));
        assertThat(listingStatus()).isEqualTo("fully_funded"); // not disbursed
    }

    @Test
    void approve_with_a_stale_mfa_assertion_is_401() throws Exception {
        draft(maker, listingId).andExpect(status().isCreated());
        jdbc.update("UPDATE auth_otp_challenge SET consumed_at = now() - interval '10 minutes' "
                + "WHERE assertion_id = (SELECT mfa_assertion_id FROM auth_session WHERE session_id = ?)",
                UUID.fromString(checker));
        approve(checker, listingId)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error_code").value("mfa_assertion_expired"));
        assertThat(listingStatus()).isEqualTo("fully_funded");
    }

    @Test
    void a_second_disbursement_draft_for_the_same_listing_is_rejected() throws Exception { // one per listing (PI.2)
        draft(maker, listingId).andExpect(status().isCreated());
        draft(checker, listingId).andExpect(status().is4xxClientError()); // different command_id, same listing
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cash_payout_instruction WHERE listing_id = ?",
                Integer.class, listingId)).isEqualTo(1);
    }

    @Test
    void draft_by_a_non_treasury_actor_is_403() throws Exception { // SoD
        String ops = bearerFor(seedAdminWithRoles("ops_executive"));
        draft(ops, listingId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));
    }

    // --- helpers -----------------------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions draft(String bearer, UUID listing) throws Exception {
        return mvc.perform(envelope(post("/listings/{id}/disbursement/draft", listing), bearer));
    }

    private org.springframework.test.web.servlet.ResultActions approve(String bearer, UUID listing) throws Exception {
        return mvc.perform(envelope(post("/listings/{id}/disbursement/approve", listing), bearer));
    }

    private MockHttpServletRequestBuilder envelope(MockHttpServletRequestBuilder builder, String bearer) {
        return builder
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", UUID.randomUUID().toString());
    }

    private String payoutStatus() {
        return jdbc.queryForObject("SELECT status::text FROM cash_payout_instruction WHERE listing_id = ?",
                String.class, listingId);
    }

    private String listingStatus() {
        return jdbc.queryForObject("SELECT status::text FROM deal_listing WHERE listing_id = ?", String.class, listingId);
    }

    private UUID seedListing(boolean allSigned) {
        UUID id = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        jdbc.update("INSERT INTO deal_invoice (invoice_id, supplier_id, buyer_id, invoice_number, face_value, "
                        + "invoice_date, tenor_days, due_date, status) "
                        + "VALUES (?, ?, ?, ?, ?, '2026-06-01', 60, '2026-07-31', 'listed')",
                invoiceId, supplierId, buyerId, "INV-" + invoiceId, 60_000_000L);
        jdbc.update("INSERT INTO deal_listing (listing_id, invoice_id, supplier_id, buyer_id, status, "
                        + "funding_target, committed_total, all_signed) "
                        + "VALUES (?, ?, ?, ?, 'fully_funded', ?, ?, ?)",
                id, invoiceId, supplierId, buyerId, FUNDING_TARGET, FUNDING_TARGET, allSigned);
        return id;
    }
}
