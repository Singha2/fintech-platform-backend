package com.arthvritt.platform.assignment;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WS-6 assignment → all-signed (see docs/modules/WS-6-assignment-all-signed.md §7): on a fully_funded
 * listing, the single investor's MIA is signed via the M5c signing ACL (inline) and the assignment set
 * reaches all_signed, flipping {@code deal_listing.all_signed} TRUE — the C27 disbursement gate.
 */
class AssignmentAllSignedTest extends AbstractEdgeHttpTest {

    private static final long FUNDING_TARGET = 50_000_000L;

    private String ops;
    private UUID adminUserId;
    private UUID listingId;
    private UUID investorId;

    @BeforeEach
    void seed() {
        notifier.clear();
        AbstractEdgeHttpTest.Seeded opsAdmin = seedAdminWithRoles("ops_executive");
        ops = bearerFor(opsAdmin);
        adminUserId = opsAdmin.adminUserId();
        investorId = seedActiveInvestor();
        seedFullyFundedListingWithConfirmedSubscription(investorId);
    }

    @Test
    void request_then_complete_signing_reaches_all_signed_and_opens_the_gate() throws Exception {
        UUID set = requestSet();
        assertThat(setStatus()).isEqualTo("in_progress");
        assertThat(totalCount()).isEqualTo(1);
        assertThat(listingAllSigned()).isFalse(); // C27 gate closed until signing completes

        send(post("/listings/{id}/assignment-set/complete-signing", listingId));

        assertThat(setStatus()).isEqualTo("all_signed");
        assertThat(signedCount()).isEqualTo(1);
        assertThat(listingAllSigned()).isTrue(); // C27 gate open
        // MIA signed with a cert; the signature request completed with a cert.
        assertThat(jdbc.queryForObject("SELECT status::text FROM legal_master_agreement WHERE party_id = ?",
                String.class, investorId)).isEqualTo("signed");
        assertThat(jdbc.queryForObject(
                "SELECT signature_cert_serial FROM legal_master_agreement WHERE party_id = ?",
                String.class, investorId)).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM legal_signature_request "
                + "WHERE status = 'completed' AND cert_serial IS NOT NULL", Integer.class)).isEqualTo(1);
        // The AllSigned audit envelope is chained to the REAL assignment set, not a synthetic command id.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_event "
                + "WHERE event_type = 'assignment.AssignmentSet.AllSigned' AND aggregate_id = ?",
                Integer.class, set)).isEqualTo(1);
    }

    @Test
    void request_by_a_non_ops_actor_is_403() throws Exception { // SoD
        String compliance = bearerFor(seedAdminWithRoles("compliance_reviewer"));
        mvc.perform(post("/listings/{id}/assignment-set/request", listingId)
                        .header("Authorization", "Bearer " + compliance)
                        .header("X-Command-Id", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));
    }

    @Test
    void requesting_a_second_set_for_the_same_listing_is_rejected() throws Exception { // AS.1
        requestSet();
        mvc.perform(post("/listings/{id}/assignment-set/request", listingId)
                        .header("Authorization", "Bearer " + ops)
                        .header("X-Command-Id", UUID.randomUUID().toString())) // different command_id
                .andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM legal_assignment_set WHERE listing_id = ?",
                Integer.class, listingId)).isEqualTo(1);
    }

    @Test
    void get_assignment_set_returns_the_set_and_the_gate_flag() throws Exception {
        requestSet();
        mvc.perform(get("/listings/{id}/assignment-set", listingId).header("Authorization", "Bearer " + ops))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("in_progress"))
                .andExpect(jsonPath("$.total_count").value(1))
                .andExpect(jsonPath("$.all_signed").value(false));
    }

    // --- helpers -----------------------------------------------------------------------------------

    private UUID requestSet() throws Exception {
        MvcResult res = mvc.perform(post("/listings/{id}/assignment-set/request", listingId)
                        .header("Authorization", "Bearer " + ops)
                        .header("X-Command-Id", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(node(res).get("aggregate_id").asText());
    }

    private void send(MockHttpServletRequestBuilder builder) throws Exception {
        mvc.perform(builder
                        .header("Authorization", "Bearer " + ops)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is2xxSuccessful());
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

    private void seedFullyFundedListingWithConfirmedSubscription(UUID investor) {
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
        jdbc.update("INSERT INTO sub_subscription (subscription_id, listing_id, investor_id, amount, status, "
                        + "expected_inflow_amount) VALUES (?, ?, ?, ?, 'confirmed', ?)",
                UUID.randomUUID(), listingId, investor, FUNDING_TARGET, FUNDING_TARGET);
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
