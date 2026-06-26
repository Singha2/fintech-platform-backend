package com.arthvritt.platform.assignment;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M12-A (DL-BE-052) — multi-investor assignment. On a fully_funded listing with N confirmed subscriptions,
 * `request` opens one set with total_count = N (a leg + MIA + signature request per investor); the C27
 * gate (`deal_listing.all_signed`) opens only when EVERY leg is signed (AS.3/AS.5).
 */
class AssignmentMultiLegTest extends AbstractEdgeHttpTest {

    private static final long LEG = 25_000_000L;
    private static final long FUNDING_TARGET = 50_000_000L; // two legs of 25M

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
    void the_gate_opens_only_when_every_leg_is_signed() throws Exception {
        requestSet();
        assertThat(totalCount()).isEqualTo(2);
        assertThat(setStatus()).isEqualTo("in_progress");

        completeSigning(investorA);
        assertThat(signedCount()).isEqualTo(1);
        assertThat(setStatus()).isEqualTo("in_progress"); // one leg left
        assertThat(listingAllSigned()).isFalse();         // C27 gate still closed

        completeSigning(investorB);
        assertThat(signedCount()).isEqualTo(2);
        assertThat(setStatus()).isEqualTo("all_signed");
        assertThat(listingAllSigned()).isTrue();           // gate open

        // Both investors' MIAs signed with a cert (scoped to this listing's two investors).
        assertThat(jdbc.queryForObject("SELECT count(*) FROM legal_master_agreement "
                + "WHERE status = 'signed' AND signature_cert_serial IS NOT NULL AND party_id IN (?, ?)",
                Integer.class, investorA, investorB)).isEqualTo(2);
    }

    @Test
    void completing_an_unknown_investor_leg_is_rejected() throws Exception {
        requestSet();
        mvc.perform(completeRequest(UUID.randomUUID()))
                .andExpect(status().is4xxClientError());
        assertThat(signedCount()).isZero();
    }

    @Test
    void completing_a_leg_twice_is_rejected_and_not_double_counted() throws Exception {
        requestSet();
        completeSigning(investorA);
        mvc.perform(completeRequest(investorA)) // distinct command id, already signed
                .andExpect(status().is4xxClientError());
        assertThat(signedCount()).isEqualTo(1);
        assertThat(setStatus()).isEqualTo("in_progress");
    }

    @Test
    void complete_signing_by_a_non_ops_actor_is_403() throws Exception { // SoD
        requestSet();
        String compliance = bearerFor(seedAdminWithRoles("compliance_reviewer"));
        mvc.perform(post("/listings/{id}/assignment-set/complete-signing", listingId)
                        .header("Authorization", "Bearer " + compliance)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("investor_id", investorA.toString()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));
    }

    // --- helpers -----------------------------------------------------------------------------------

    private UUID requestSet() throws Exception {
        MvcResult res = mvc.perform(post("/listings/{id}/assignment-set/request", listingId)
                        .header("Authorization", "Bearer " + ops)
                        .header("X-Command-Id", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(node(res).get("aggregate_id").asText());
    }

    private void completeSigning(UUID investor) throws Exception {
        mvc.perform(completeRequest(investor)).andExpect(status().is2xxSuccessful());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder completeRequest(UUID investor)
            throws Exception {
        return post("/listings/{id}/assignment-set/complete-signing", listingId)
                .header("Authorization", "Bearer " + ops)
                .header("X-Command-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("investor_id", investor.toString())));
    }

    private String setStatus() {
        return jdbc.queryForObject("SELECT status::text FROM legal_assignment_set WHERE listing_id = ?",
                String.class, listingId);
    }

    private int totalCount() {
        return jdbc.queryForObject("SELECT total_count FROM legal_assignment_set WHERE listing_id = ?",
                Integer.class, listingId);
    }

    private int signedCount() {
        return jdbc.queryForObject("SELECT signed_count FROM legal_assignment_set WHERE listing_id = ?",
                Integer.class, listingId);
    }

    private boolean listingAllSigned() {
        return jdbc.queryForObject("SELECT all_signed FROM deal_listing WHERE listing_id = ?",
                Boolean.class, listingId);
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
