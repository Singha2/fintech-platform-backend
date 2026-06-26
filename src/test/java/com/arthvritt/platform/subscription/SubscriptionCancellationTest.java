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
 * M11-A (DL-BE-049) — pre-confirmation cancellation + release. Cancelling a {@code committed} subscription
 * flips it to {@code cancelled_by_investor} and releases the host listing's {@code committed_total} (the
 * inverse of the WS-5 coordinated commit); a {@code fully_funded} listing reopens to {@code live}. A
 * {@code confirmed} (funded) subscription can no longer be cancelled (S.2).
 */
class SubscriptionCancellationTest extends AbstractEdgeHttpTest {

    private static final long FUNDING_TARGET = 50_000_000L;

    @Value("${platform.webhook.banking.secret}") private String webhookSecret;

    private String ops;
    private UUID adminUserId;
    private UUID listingId;
    private UUID vaId;
    private UUID investorId;

    @BeforeEach
    void seed() {
        notifier.clear();
        Seeded opsAdmin = seedAdminWithRoles("ops_executive");
        ops = bearerFor(opsAdmin);
        adminUserId = opsAdmin.adminUserId();
        seedLiveListingWithVa();
        investorId = seedActiveInvestor();
    }

    @Test
    void cancelling_a_committed_subscription_releases_the_listing() throws Exception {
        UUID sub = commit(investorId, 30_000_000L);
        assertThat(committedTotal()).isEqualTo(30_000_000L);
        assertThat(listingStatus()).isEqualTo("live");

        cancel(sub);

        assertThat(subscriptionStatus(sub)).isEqualTo("cancelled_by_investor");
        assertThat(committedTotal()).isZero();
        assertThat(listingStatus()).isEqualTo("live");
    }

    @Test
    void cancelling_a_subscription_that_filled_the_listing_reopens_it() throws Exception {
        UUID sub = commit(investorId, FUNDING_TARGET);
        assertThat(listingStatus()).isEqualTo("fully_funded");

        cancel(sub);

        assertThat(subscriptionStatus(sub)).isEqualTo("cancelled_by_investor");
        assertThat(committedTotal()).isZero();
        assertThat(listingStatus()).isEqualTo("live"); // reopened
        assertThat(envelopes("listing.Listing.FundingReleased")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void a_confirmed_subscription_cannot_be_cancelled() throws Exception { // S.2 (post-funds)
        UUID sub = commit(investorId, FUNDING_TARGET);
        postInflow(vaId, FUNDING_TARGET, "UTR-" + UUID.randomUUID(), "evt-" + UUID.randomUUID())
                .andExpect(status().isOk());
        assertThat(subscriptionStatus(sub)).isEqualTo("confirmed");

        mvc.perform(withEnvelope(post("/subscriptions/{id}/cancel", sub), ops, sub))
                .andExpect(status().is4xxClientError());
        assertThat(subscriptionStatus(sub)).isEqualTo("confirmed");
        assertThat(committedTotal()).isEqualTo(FUNDING_TARGET); // not released
    }

    @Test
    void cancel_by_a_non_ops_actor_is_403() throws Exception { // SoD
        UUID sub = commit(investorId, 30_000_000L);
        String compliance = bearerFor(seedAdminWithRoles("compliance_reviewer"));
        mvc.perform(withEnvelope(post("/subscriptions/{id}/cancel", sub), compliance, sub))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));
        assertThat(subscriptionStatus(sub)).isEqualTo("committed");
    }

    // --- helpers -----------------------------------------------------------------------------------

    private void cancel(UUID sub) throws Exception {
        mvc.perform(withEnvelope(post("/subscriptions/{id}/cancel", sub), ops, sub))
                .andExpect(status().is2xxSuccessful());
    }

    private MockHttpServletRequestBuilder withEnvelope(MockHttpServletRequestBuilder builder, String bearer, UUID sub)
            throws Exception {
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

    private long committedTotal() {
        return jdbc.queryForObject("SELECT committed_total FROM deal_listing WHERE listing_id = ?", Long.class, listingId);
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
