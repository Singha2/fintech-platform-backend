package com.arthvritt.platform.e2e;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Walking Skeleton capstone (docs/modules/WS-walking-skeleton.md §1): one invoice walks the money-flow
 * spine WS-4 → WS-7 <b>over HTTP through the real controllers</b> — create listing → ops-checks → price +
 * go-live (maker-checker #1) → subscribe to 100% → HMAC inflow → assignment + sign → disbursement
 * (maker-checker #2) → {@code disbursed}. Upstream onboarding (supplier/buyer/investor, proven in WS-1/2/3)
 * is seeded active; this test is the proof that the slices compose into {@code listed → disbursed}.
 */
class WalkingSkeletonE2ETest extends AbstractEdgeHttpTest {

    @Value("${platform.webhook.banking.secret}") private String webhookSecret;

    @Test
    void one_invoice_walks_listed_to_disbursed_over_http() throws Exception {
        // Actors.
        String ops = bearerFor(seedAdminWithRoles("ops_executive"));
        AbstractEdgeHttpTest.Seeded tsMakerAdmin = seedAdminWithRoles("treasury_and_settlement");
        String treasuryA = bearerFor(tsMakerAdmin);
        String treasuryB = bearerFor(seedAdminWithRoles("treasury_and_settlement")); // ≠ A, for disbursement checker

        // Seeded upstream context (active supplier + buyer + investor + pricing band).
        UUID supplierId = seedActiveSupplier();
        UUID buyerId = seedActiveBuyer(tsMakerAdmin.adminUserId());
        seedPricingBand(buyerId, "31_60d", 1000, 1500, 200);
        UUID investorId = seedActiveInvestor(tsMakerAdmin.adminUserId());

        // WS-4 + M9-C: create → full DL-027 ops-checks → snapshot (maker) → go-live (checker). face 100000000.
        UUID listing = createListing(ops, supplierId, buyerId);
        send(post("/listings/{id}/start-ops-checks", listing), ops, listing, Map.of());
        send(post("/listings/{id}/record-ops-check", listing), ops, listing, Map.of("check_name", "irn_validity"));
        for (String check : new String[]{"eway_bill_match", "buyer_supplier_relationship", "duplicate_check",
                "supplier_exposure_cap", "buyer_limit_headroom", "document_completeness"}) {
            send(post("/listings/{id}/record-ops-check", listing), ops, listing,
                    Map.of("check_name", check, "outcome", "passed"));
        }
        send(post("/listings/{id}/complete-ops-checks", listing), ops, listing, Map.of());
        send(post("/listings/{id}/record-buyer-ack", listing), ops, listing,
                Map.of("outcome", "acknowledged", "method", "email"));
        send(post("/listings/{id}/snapshot-and-ready", listing), ops, listing, Map.of("rate_bps", 1200));
        long fundingTarget = jdbc.queryForObject("SELECT funding_target FROM deal_listing WHERE listing_id = ?",
                Long.class, listing);
        send(post("/listings/{id}/approve-go-live", listing), treasuryA, listing, Map.of());
        assertThat(listingStatus(listing)).isEqualTo("live");
        UUID vaId = jdbc.queryForObject("SELECT va_id FROM deal_listing WHERE listing_id = ?", UUID.class, listing);

        // WS-5: subscribe the full funding_target → fully_funded, then the HMAC inflow → confirmed.
        mvc.perform(post("/listings/{id}/subscriptions/commit", listing)
                        .header("Authorization", "Bearer " + ops)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("investor_id", investorId.toString(),
                                "amount_paise", fundingTarget))))
                .andExpect(status().isCreated());
        assertThat(listingStatus(listing)).isEqualTo("fully_funded");
        postInflow(vaId, fundingTarget).andExpect(status().isOk());

        // WS-6: assignment set → single-leg signed → all_signed (the C27 gate).
        mvc.perform(post("/listings/{id}/assignment-set/request", listing)
                        .header("Authorization", "Bearer " + ops)
                        .header("X-Command-Id", UUID.randomUUID().toString()))
                .andExpect(status().isCreated());
        mvc.perform(post("/listings/{id}/assignment-set/complete-signing", listing)
                        .header("Authorization", "Bearer " + ops)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("investor_id", investorId.toString()))))
                .andExpect(status().is2xxSuccessful());
        assertThat(jdbc.queryForObject("SELECT all_signed FROM deal_listing WHERE listing_id = ?",
                Boolean.class, listing)).isTrue();

        // WS-7: disbursement draft (maker) → approve (checker ≠ maker) → disbursed.
        mvc.perform(post("/listings/{id}/disbursement/draft", listing)
                        .header("Authorization", "Bearer " + treasuryA)
                        .header("X-Command-Id", UUID.randomUUID().toString()))
                .andExpect(status().isCreated());
        mvc.perform(post("/listings/{id}/disbursement/approve", listing)
                        .header("Authorization", "Bearer " + treasuryB)
                        .header("X-Command-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk());

        // The skeleton walked: listed → disbursed.
        assertThat(listingStatus(listing)).isEqualTo("disbursed");
        // G10 still holds at the end: Σ confirmed = committed = observed = funding_target.
        long confirmed = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM sub_subscription "
                + "WHERE listing_id = ? AND status = 'confirmed'", Long.class, listing);
        assertThat(confirmed).isEqualTo(fundingTarget);
        assertThat(jdbc.queryForObject("SELECT observed_inflow_total FROM cash_virtual_account WHERE va_id = ?",
                Long.class, vaId)).isEqualTo(fundingTarget);
    }

    // --- helpers -----------------------------------------------------------------------------------

    private UUID createListing(String ops, UUID supplierId, UUID buyerId) throws Exception {
        MvcResult res = mvc.perform(post("/listings")
                        .header("Authorization", "Bearer " + ops)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "supplier_id", supplierId.toString(), "buyer_id", buyerId.toString(),
                                "invoice_number", "INV-" + UUID.randomUUID(), "face_value_paise", 100_000_000L,
                                "invoice_date", "2026-06-01", "tenor_days", 60))))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(node(res).get("aggregate_id").asText());
    }

    private void send(MockHttpServletRequestBuilder builder, String bearer, UUID listing, Map<String, Object> body)
            throws Exception {
        int version = jdbc.queryForObject("SELECT aggregate_version FROM deal_listing WHERE listing_id = ?",
                Integer.class, listing);
        mvc.perform(builder
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .header("X-Aggregate-Version", String.valueOf(version))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is2xxSuccessful());
    }

    private org.springframework.test.web.servlet.ResultActions postInflow(UUID va, long amount) throws Exception {
        String body = json.writeValueAsString(Map.of("va_id", va.toString(), "amount_paise", amount,
                "utr", "UTR-" + UUID.randomUUID(), "event_id", "evt-" + UUID.randomUUID()));
        String ts = String.valueOf(Instant.now().toEpochMilli());
        return mvc.perform(post("/webhooks/banking/{vendor}/inflow.received", "stub-escrow")
                .header("X-Timestamp", ts).header("X-Signature", sign(ts, body))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String sign(String timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    private String listingStatus(UUID listing) {
        return jdbc.queryForObject("SELECT status::text FROM deal_listing WHERE listing_id = ?", String.class, listing);
    }

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

    private UUID seedActiveInvestor(UUID issuedBy) {
        UUID identityId = UUID.randomUUID();
        UUID inviteId = UUID.randomUUID();
        UUID investor = UUID.randomUUID();
        String email = "inv-" + investor + "@arthvritt.test";
        jdbc.update("INSERT INTO auth_identity (identity_id, kind, email, phone_e164, display_name, status) "
                        + "VALUES (?, 'investor'::identity_kind_enum, ?, ?, 'Investor', 'active'::identity_status_enum)",
                identityId, email, phone());
        jdbc.update("INSERT INTO inv_invite (invite_id, email_hash, phone_hash, issued_by, expiry_at, status) "
                        + "VALUES (?, ?, ?, ?, now() + interval '14 days', 'pending')",
                inviteId, email.getBytes(StandardCharsets.UTF_8), "p".getBytes(StandardCharsets.UTF_8), issuedBy);
        jdbc.update("INSERT INTO inv_account (investor_id, identity_id, invite_id, sub_type, status) "
                        + "VALUES (?, ?, ?, 'resident_individual'::inv_sub_type, 'active'::inv_account_status)",
                investor, identityId, inviteId);
        return investor;
    }
}
