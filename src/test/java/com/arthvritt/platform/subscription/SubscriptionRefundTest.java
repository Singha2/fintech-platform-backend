package com.arthvritt.platform.subscription;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
 * M11-B (DL-BE-050) — funding shortfall → refund. An under-subscribed listing whose window has closed is
 * declared a shortfall (ops, L.8/L.9) → {@code funding_failed_refunded}; a funded ({@code confirmed})
 * subscription is then refunded (treasury) inline through the BC18 escrow ACL + a {@code kind=refund} payout
 * row → {@code refunded}.
 */
class SubscriptionRefundTest extends AbstractEdgeHttpTest {

    private static final long FUNDING_TARGET = 50_000_000L;
    private static final long PARTIAL = 30_000_000L;

    @Value("${platform.webhook.banking.secret}") private String webhookSecret;

    private String ops;
    private String treasury;
    private UUID adminUserId;
    private UUID listingId;
    private UUID vaId;
    private UUID investorId;

    @BeforeEach
    void seed() {
        notifier.clear();
        Seeded opsAdmin = seedAdminWithRoles("ops_executive");
        ops = bearerFor(opsAdmin);
        treasury = bearerFor(seedAdminWithRoles("treasury_and_settlement"));
        adminUserId = opsAdmin.adminUserId();
    }

    @Test
    void declaring_a_shortfall_on_an_under_funded_closed_listing_fails_it() throws Exception {
        seedLiveListing(true); // window closed
        investorId = seedActiveInvestor();
        commit(investorId, PARTIAL);
        assertThat(listingStatus()).isEqualTo("live");

        declareShortfall();
        assertThat(listingStatus()).isEqualTo("funding_failed_refunded");
    }

    @Test
    void declaring_before_the_window_closes_is_rejected() throws Exception {
        seedLiveListing(false); // window still open
        investorId = seedActiveInvestor();
        commit(investorId, PARTIAL);

        mvc.perform(withListingEnvelope(post("/listings/{id}/declare-funding-shortfall", listingId), ops))
                .andExpect(status().is4xxClientError());
        assertThat(listingStatus()).isEqualTo("live");
    }

    @Test
    void declaring_a_shortfall_on_a_fully_funded_listing_is_rejected() throws Exception {
        seedLiveListing(true);
        investorId = seedActiveInvestor();
        commit(investorId, FUNDING_TARGET); // fills → fully_funded
        assertThat(listingStatus()).isEqualTo("fully_funded");

        mvc.perform(withListingEnvelope(post("/listings/{id}/declare-funding-shortfall", listingId), ops))
                .andExpect(status().is4xxClientError());
        assertThat(listingStatus()).isEqualTo("fully_funded");
    }

    @Test
    void refunding_a_confirmed_subscription_returns_money_and_marks_it_refunded() throws Exception {
        seedLiveListing(true);
        investorId = seedActiveInvestor();
        UUID sub = commit(investorId, PARTIAL);
        postInflow(vaId, PARTIAL, "UTR-" + UUID.randomUUID(), "evt-" + UUID.randomUUID())
                .andExpect(status().isOk());
        assertThat(subscriptionStatus(sub)).isEqualTo("confirmed");

        declareShortfall();
        recordRefund(sub);

        assertThat(subscriptionStatus(sub)).isEqualTo("refunded");
        // A kind=refund payout instruction was executed for this subscription.
        Integer refunds = jdbc.queryForObject(
                "SELECT count(*) FROM cash_payout_instruction WHERE subscription_id = ? AND kind = 'refund' "
                        + "AND status = 'executed'", Integer.class, sub);
        assertThat(refunds).isEqualTo(1);
        // The escrow ACL actually executed the refund (audit envelope).
        assertThat(envelopes("banking.RefundWebhookProcessed")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void a_second_refund_on_the_same_subscription_does_not_double_pay() throws Exception {
        seedLiveListing(true);
        investorId = seedActiveInvestor();
        UUID sub = commit(investorId, PARTIAL);
        postInflow(vaId, PARTIAL, "UTR-" + UUID.randomUUID(), "evt-" + UUID.randomUUID())
                .andExpect(status().isOk());
        declareShortfall();
        recordRefund(sub);

        // A second refund attempt (distinct command_id) is rejected — the sub is already refunded — and no
        // second kind=refund payout row is created (the derived payout id collides on the PK).
        mvc.perform(withSubEnvelope(post("/subscriptions/{id}/record-refund", sub), treasury, sub))
                .andExpect(status().is4xxClientError());
        Integer refunds = jdbc.queryForObject(
                "SELECT count(*) FROM cash_payout_instruction WHERE subscription_id = ? AND kind = 'refund'",
                Integer.class, sub);
        assertThat(refunds).isEqualTo(1);
    }

    @Test
    void recording_a_refund_without_a_declared_shortfall_is_rejected() throws Exception {
        seedLiveListing(true);
        investorId = seedActiveInvestor();
        UUID sub = commit(investorId, PARTIAL); // committed, no shortfall declared
        mvc.perform(withSubEnvelope(post("/subscriptions/{id}/record-refund", sub), treasury, sub))
                .andExpect(status().is4xxClientError());
        assertThat(subscriptionStatus(sub)).isEqualTo("committed");
    }

    @Test
    void record_refund_by_a_non_treasury_actor_is_403() throws Exception { // SoD
        seedLiveListing(true);
        investorId = seedActiveInvestor();
        UUID sub = commit(investorId, PARTIAL);
        declareShortfall();
        mvc.perform(withSubEnvelope(post("/subscriptions/{id}/record-refund", sub), ops, sub))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));
        assertThat(subscriptionStatus(sub)).isEqualTo("committed");
    }

    // --- helpers -----------------------------------------------------------------------------------

    private void declareShortfall() throws Exception {
        mvc.perform(withListingEnvelope(post("/listings/{id}/declare-funding-shortfall", listingId), ops))
                .andExpect(status().is2xxSuccessful());
    }

    private void recordRefund(UUID sub) throws Exception {
        mvc.perform(withSubEnvelope(post("/subscriptions/{id}/record-refund", sub), treasury, sub))
                .andExpect(status().is2xxSuccessful());
    }

    private MockHttpServletRequestBuilder withListingEnvelope(MockHttpServletRequestBuilder builder, String bearer) {
        return builder
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", UUID.randomUUID().toString())
                .header("X-Aggregate-Version", String.valueOf(listingVersion()))
                .contentType(MediaType.APPLICATION_JSON);
    }

    private MockHttpServletRequestBuilder withSubEnvelope(MockHttpServletRequestBuilder builder, String bearer, UUID sub) {
        return builder
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", UUID.randomUUID().toString())
                .header("X-Aggregate-Version", String.valueOf(subscriptionVersion(sub)))
                .contentType(MediaType.APPLICATION_JSON);
    }

    private UUID commit(UUID investor, long amount) throws Exception {
        MvcResult res = mvc.perform(post("/listings/{id}/subscriptions/commit", listingId)
                        .header("Authorization", "Bearer " + ops)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("investor_id", investor.toString(),
                                "amount_paise", amount))))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(node(res).get("aggregate_id").asText());
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
            return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String listingStatus() {
        return jdbc.queryForObject("SELECT status::text FROM deal_listing WHERE listing_id = ?", String.class, listingId);
    }

    private int listingVersion() {
        return jdbc.queryForObject("SELECT aggregate_version FROM deal_listing WHERE listing_id = ?",
                Integer.class, listingId);
    }

    private String subscriptionStatus(UUID sub) {
        return jdbc.queryForObject("SELECT status::text FROM sub_subscription WHERE subscription_id = ?",
                String.class, sub);
    }

    private int subscriptionVersion(UUID sub) {
        return jdbc.queryForObject("SELECT aggregate_version FROM sub_subscription WHERE subscription_id = ?",
                Integer.class, sub);
    }

    private int envelopes(String eventType) {
        return jdbc.queryForObject("SELECT count(*) FROM sys_audit_event WHERE event_type = ?", Integer.class, eventType);
    }

    private void seedLiveListing(boolean windowClosed) {
        listingId = UUID.randomUUID();
        vaId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        String window = windowClosed ? "now() - interval '1 day'" : "now() + interval '5 days'";
        jdbc.update("INSERT INTO deal_invoice (invoice_id, supplier_id, buyer_id, invoice_number, face_value, "
                        + "invoice_date, tenor_days, due_date, status) "
                        + "VALUES (?, ?, ?, ?, ?, '2026-06-01', 60, '2026-07-31', 'listed')",
                invoiceId, supplierId, buyerId, "INV-" + invoiceId, 60_000_000L);
        jdbc.update("INSERT INTO deal_listing (listing_id, invoice_id, supplier_id, buyer_id, status, "
                        + "funding_target, committed_total, va_id, funding_window_close_at) "
                        + "VALUES (?, ?, ?, ?, 'live', ?, 0, ?, " + window + ")",
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
