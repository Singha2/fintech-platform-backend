package com.arthvritt.platform.subscription;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-18 Part 2 (M11-B §7) — investor self-commit. An investor commits under its <b>own</b> session; the
 * {@code investor_id} is derived from the session, never the body. Ops-on-behalf (admin OPS caller) is retained
 * (no S12 regression). Eligibility (active, ≥ min ticket, within headroom, live listing) is enforced server-side
 * as clean domain errors, and the five non-negotiables that apply (idempotency, audit) hold for the investor actor.
 */
class InvestorSelfCommitTest extends AbstractEdgeHttpTest {

    private static final long FUNDING_TARGET = 50_000_000L; // ₹5L
    private static final long TICKET = 2_000_000L;          // ₹20k (≥ the ₹10k minimum)

    private UUID listingId;

    @BeforeEach
    void seed() {
        notifier.clear();
        listingId = seedLiveListing(FUNDING_TARGET);
    }

    @Test
    void investor_commits_to_own_account_without_supplying_an_id() throws Exception {
        InvestorLogin il = seedActiveInvestorWithLogin();
        String bearer = bearerFor(il.login()); // existing password flow — decoupled from Part 1

        mvc.perform(commit(bearer, UUID.randomUUID(), Map.of("amount_paise", TICKET)))
                .andExpect(status().isCreated());

        assertThat(committedTotal()).isEqualTo(TICKET);
        assertThat(jdbc.queryForObject("SELECT investor_id FROM sub_subscription WHERE listing_id = ?",
                UUID.class, listingId)).isEqualTo(il.investorId());
        // The commit envelope is attributed to the investor actor (not admin_user).
        assertThat(jdbc.queryForObject("SELECT actor->>'actor_type' FROM sys_audit_event "
                        + "WHERE event_type = 'subscription.Subscription.Committed' AND payload->>'listing_id' = ?",
                String.class, listingId.toString())).isEqualTo("investor");
    }

    @Test
    void an_investor_cannot_commit_for_another_investor() throws Exception {
        InvestorLogin a = seedActiveInvestorWithLogin();
        InvestorLogin b = seedActiveInvestorWithLogin();
        String bearerA = bearerFor(a.login());

        mvc.perform(commit(bearerA, UUID.randomUUID(),
                        Map.of("investor_id", b.investorId().toString(), "amount_paise", TICKET)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("cross_tenant_read"));

        assertThat(subscriptionCount()).isZero();
    }

    @Test
    void a_below_minimum_ticket_is_rejected() throws Exception {
        String bearer = bearerFor(seedActiveInvestorWithLogin().login());
        mvc.perform(commit(bearer, UUID.randomUUID(), Map.of("amount_paise", 999_999L)))
                .andExpect(status().isBadRequest());
        assertThat(subscriptionCount()).isZero();
    }

    @Test
    void a_commit_over_headroom_is_rejected() throws Exception {
        String bearer = bearerFor(seedActiveInvestorWithLogin().login());
        mvc.perform(commit(bearer, UUID.randomUUID(), Map.of("amount_paise", FUNDING_TARGET + 1)))
                .andExpect(status().is4xxClientError());
        assertThat(committedTotal()).isZero();
    }

    @Test
    void a_commit_on_a_non_live_listing_is_rejected() throws Exception {
        jdbc.update("UPDATE deal_listing SET status = 'fully_funded' WHERE listing_id = ?", listingId);
        String bearer = bearerFor(seedActiveInvestorWithLogin().login());
        mvc.perform(commit(bearer, UUID.randomUUID(), Map.of("amount_paise", TICKET)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void ops_on_behalf_commit_still_works() throws Exception { // no S12 regression (DoR-6)
        InvestorLogin il = seedActiveInvestorWithLogin();
        String ops = bearerFor(seedAdminWithRoles("ops_executive"));
        mvc.perform(commit(ops, UUID.randomUUID(),
                        Map.of("investor_id", il.investorId().toString(), "amount_paise", TICKET)))
                .andExpect(status().isCreated());
        assertThat(committedTotal()).isEqualTo(TICKET);
    }

    @Test
    void a_replayed_investor_command_is_idempotent() throws Exception {
        String bearer = bearerFor(seedActiveInvestorWithLogin().login());
        UUID commandId = UUID.randomUUID();
        mvc.perform(commit(bearer, commandId, Map.of("amount_paise", TICKET))).andExpect(status().isCreated());
        mvc.perform(commit(bearer, commandId, Map.of("amount_paise", TICKET))).andExpect(status().isOk()); // replay
        assertThat(subscriptionCount()).isEqualTo(1);
        assertThat(committedTotal()).isEqualTo(TICKET); // not doubled
    }

    // --- helpers -----------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder commit(String bearer, UUID commandId, Map<String, Object> body)
            throws Exception {
        return post("/listings/{id}/subscriptions/commit", listingId)
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", commandId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
    }

    private long committedTotal() {
        return jdbc.queryForObject("SELECT committed_total FROM deal_listing WHERE listing_id = ?", Long.class, listingId);
    }

    private int subscriptionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM sub_subscription WHERE listing_id = ?", Integer.class, listingId);
    }

    /** A {@code live} listing with a funding target (bare supplier/buyer UUIDs — no FK on those columns). */
    private UUID seedLiveListing(long fundingTarget) {
        UUID listing = UUID.randomUUID();
        UUID invoice = UUID.randomUUID();
        jdbc.update("INSERT INTO deal_invoice (invoice_id, supplier_id, buyer_id, invoice_number, face_value, "
                        + "invoice_date, tenor_days, due_date, status) "
                        + "VALUES (?, ?, ?, ?, ?, '2026-06-01', 60, '2026-07-31', 'listed')",
                invoice, UUID.randomUUID(), UUID.randomUUID(), "INV-" + invoice, 60_000_000L);
        jdbc.update("INSERT INTO deal_listing (listing_id, invoice_id, supplier_id, buyer_id, status, "
                        + "funding_target, committed_total) VALUES (?, ?, ?, ?, 'live', ?, 0)",
                listing, invoice, UUID.randomUUID(), UUID.randomUUID(), fundingTarget);
        return listing;
    }
}
