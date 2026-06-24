package com.arthvritt.platform.subscription;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WS-5 subscribe → fully-funded → inflow webhook (see docs/modules/WS-5-subscribe-fully-funded.md §7): the
 * funding-equality (G10) slice and the platform's first inbound webhook. Drives commit → fully_funded →
 * HMAC-signed inflow → confirmed over HTTP and asserts the four-way paise equality
 * {@code Σ confirmed = committed_total = observed_inflow_total = funding_target}.
 */
class SubscribeFullyFundedTest extends AbstractEdgeHttpTest {

    private static final long FUNDING_TARGET = 50_000_000L; // ₹5L

    @Value("${platform.webhook.banking.secret}") private String webhookSecret;

    private String ops;
    private UUID adminUserId;
    private UUID listingId;
    private UUID vaId;
    private UUID investorId;

    @BeforeEach
    void seed() {
        notifier.clear();
        AbstractEdgeHttpTest.Seeded opsAdmin = seedAdminWithRoles("ops_executive");
        ops = bearerFor(opsAdmin);
        adminUserId = opsAdmin.adminUserId();
        seedLiveListingWithVa();
        investorId = seedActiveInvestor();
    }

    @Test
    void the_full_g10_chain_holds_to_the_paise() throws Exception {
        UUID subscription = commit(investorId, FUNDING_TARGET);

        // Commit fills the funding target → listing is fully_funded (committed = target).
        assertThat(listingStatus()).isEqualTo("fully_funded");
        assertThat(committedTotal()).isEqualTo(FUNDING_TARGET);
        assertThat(subscriptionStatus(subscription)).isEqualTo("committed");

        // The escrow vendor confirms the inflow via the HMAC webhook → subscription confirmed.
        postInflow(vaId, FUNDING_TARGET, "UTR-" + UUID.randomUUID(), "evt-" + UUID.randomUUID())
                .andExpect(status().isOk());

        assertThat(subscriptionStatus(subscription)).isEqualTo("confirmed");
        assertThat(observedInflowTotal()).isEqualTo(FUNDING_TARGET);
        // G10: the four-way equality, paise-exact.
        long confirmedSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM sub_subscription WHERE listing_id = ? AND status = 'confirmed'",
                Long.class, listingId);
        assertThat(confirmedSum).isEqualTo(FUNDING_TARGET);
        assertThat(confirmedSum).isEqualTo(committedTotal()).isEqualTo(observedInflowTotal()).isEqualTo(FUNDING_TARGET);
    }

    @Test
    void over_subscription_is_blocked_at_commit() throws Exception {
        commit(investorId, FUNDING_TARGET); // fills the target
        UUID secondInvestor = seedActiveInvestor();
        // A second commit would push committed_total over funding_target → rejected at commit (S.5).
        mvc.perform(commitRequest(UUID.randomUUID(), secondInvestor, 1_000_000L))
                .andExpect(status().is4xxClientError());
        assertThat(committedTotal()).isEqualTo(FUNDING_TARGET); // unchanged
    }

    @Test
    void a_below_minimum_ticket_is_rejected() throws Exception { // S.1 ₹10,000
        mvc.perform(commitRequest(UUID.randomUUID(), investorId, 999_999L))
                .andExpect(status().isBadRequest());
    }

    @Test
    void an_invalid_hmac_is_401_and_records_no_inflow() throws Exception { // C10
        commit(investorId, FUNDING_TARGET);
        String body = json.writeValueAsString(Map.of("va_id", vaId.toString(), "amount_paise", FUNDING_TARGET,
                "utr", "UTR-x", "event_id", "evt-x"));
        mvc.perform(post("/webhooks/banking/{vendor}/inflow.received", "stub-escrow")
                        .header("X-Timestamp", String.valueOf(Instant.now().toEpochMilli()))
                        .header("X-Signature", "deadbeef") // wrong
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error_code").value("signature_invalid"));
        assertThat(observedInflowTotal()).isZero();
        assertThat(envelopes("banking.WebhookSignature.Invalid")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void a_duplicate_inflow_event_is_counted_once() throws Exception { // VI.3 dedup
        commit(investorId, FUNDING_TARGET);
        String utr = "UTR-" + UUID.randomUUID();
        String eventId = "evt-" + UUID.randomUUID();
        postInflow(vaId, FUNDING_TARGET, utr, eventId).andExpect(status().isOk());
        postInflow(vaId, FUNDING_TARGET, utr, eventId).andExpect(status().isOk()); // re-delivery
        assertThat(observedInflowTotal()).isEqualTo(FUNDING_TARGET); // not doubled
    }

    @Test
    void commit_by_a_non_ops_actor_is_403() throws Exception { // SoD
        String compliance = bearerFor(seedAdminWithRoles("compliance_reviewer"));
        mvc.perform(post("/listings/{id}/subscriptions/commit", listingId)
                        .header("Authorization", "Bearer " + compliance)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("investor_id", investorId.toString(),
                                "amount_paise", FUNDING_TARGET))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));
    }

    @Test
    void two_partial_commits_fill_the_target_and_flip_fully_funded() throws Exception {
        // Exercises the atomic bump+CASE flip: the second commit reaches funding_target and must flip
        // (the flip is driven off the post-bump value, not a stale before-image read).
        commit(investorId, 30_000_000L);
        assertThat(listingStatus()).isEqualTo("live"); // not yet full
        UUID secondInvestor = seedActiveInvestor();
        commit(secondInvestor, 20_000_000L); // 30M + 20M = 50M = funding_target
        assertThat(listingStatus()).isEqualTo("fully_funded");
        assertThat(committedTotal()).isEqualTo(FUNDING_TARGET);
    }

    @Test
    void a_fractional_inflow_amount_is_rejected_not_truncated() throws Exception { // money is integer paise
        commit(investorId, FUNDING_TARGET);
        String body = "{\"va_id\":\"" + vaId + "\",\"amount_paise\":50000000.7,"
                + "\"utr\":\"UTR-x\",\"event_id\":\"evt-x\"}";
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        mvc.perform(post("/webhooks/banking/{vendor}/inflow.received", "stub-escrow")
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", sign(timestamp, body))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(observedInflowTotal()).isZero();
    }

    // --- helpers -----------------------------------------------------------------------------------

    private UUID commit(UUID investor, long amount) throws Exception {
        MvcResult res = mvc.perform(commitRequest(UUID.randomUUID(), investor, amount))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(node(res).get("aggregate_id").asText());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder commitRequest(
            UUID commandId, UUID investor, long amount) throws Exception {
        return post("/listings/{id}/subscriptions/commit", listingId)
                .header("Authorization", "Bearer " + ops)
                .header("X-Command-Id", commandId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("investor_id", investor.toString(), "amount_paise", amount)));
    }

    private org.springframework.test.web.servlet.ResultActions postInflow(UUID va, long amount, String utr,
                                                                          String eventId) throws Exception {
        String body = json.writeValueAsString(Map.of("va_id", va.toString(), "amount_paise", amount,
                "utr", utr, "event_id", eventId));
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        return mvc.perform(post("/webhooks/banking/{vendor}/inflow.received", "stub-escrow")
                .header("X-Timestamp", timestamp)
                .header("X-Signature", sign(timestamp, body))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String sign(String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sig);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String listingStatus() {
        return jdbc.queryForObject("SELECT status::text FROM deal_listing WHERE listing_id = ?", String.class, listingId);
    }

    private long committedTotal() {
        return jdbc.queryForObject("SELECT committed_total FROM deal_listing WHERE listing_id = ?", Long.class, listingId);
    }

    private long observedInflowTotal() {
        return jdbc.queryForObject("SELECT observed_inflow_total FROM cash_virtual_account WHERE va_id = ?",
                Long.class, vaId);
    }

    private String subscriptionStatus(UUID subscription) {
        return jdbc.queryForObject("SELECT status::text FROM sub_subscription WHERE subscription_id = ?",
                String.class, subscription);
    }

    private int envelopes(String eventType) {
        return jdbc.queryForObject("SELECT count(*) FROM sys_audit_event WHERE event_type = ?", Integer.class, eventType);
    }

    private void seedLiveListingWithVa() {
        listingId = UUID.randomUUID();
        vaId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        jdbc.update("INSERT INTO deal_invoice (invoice_id, supplier_id, buyer_id, invoice_number, face_value, "
                        + "invoice_date, tenor_days, due_date, status) "
                        + "VALUES (?, ?, ?, ?, ?, '2026-06-01', 60, '2026-07-31', 'listed')",
                invoiceId, supplierId, buyerId, "INV-" + invoiceId, 60_000_000L);
        jdbc.update("INSERT INTO deal_listing (listing_id, invoice_id, supplier_id, buyer_id, status, "
                        + "funding_target, committed_total, va_id) VALUES (?, ?, ?, ?, 'live', ?, 0, ?)",
                listingId, invoiceId, supplierId, buyerId, FUNDING_TARGET, vaId);
        jdbc.update("INSERT INTO cash_virtual_account (va_id, listing_id, status, expected_inflow_total, "
                        + "observed_inflow_total) VALUES (?, ?, 'created', ?, 0)", vaId, listingId, FUNDING_TARGET);
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
