package com.arthvritt.platform.investor;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M10-D · Investor self-login (read-only portal) — docs/modules/M10-D-investor-self-login.md §7/§10.
 * An investor logs in through the <b>existing</b> password → OTP flow ({@link #bearerFor}) and gets a
 * read-only portal: {@code /auth/session} carries its {@code investor_id} (SES-1), the marketplace is
 * forced to live-only (OWN-2), its own portfolio is readable but a sibling investor's is not (OWN-1), and
 * every write command it attempts is rejected {@code role_not_held} (RO-1) — it holds no admin role.
 *
 * <p><b>BE-18 (M11-B) narrows RO-1:</b> an investor may now run exactly one write — its own
 * {@code subscriptions/commit} self-commit (covered by {@code subscription/InvestorSelfCommitTest}). Every
 * <i>other</i> write still rejects {@code role_not_held}, as {@link #investor_bearer_cannot_run_onboarding_command}
 * locks. The old "investor cannot commit at all" case was retired when self-commit shipped.
 */
class InvestorSelfLoginTest extends AbstractEdgeHttpTest {

    @Test
    void investor_logs_in_over_the_existing_password_otp_flow() throws Exception {
        InvestorLogin investor = seedActiveInvestorWithLogin();

        String bearer = bearerFor(investor.login());

        assertThat(bearer).isNotBlank();
    }

    @Test
    void session_returns_investor_id_and_empty_roles_for_investor() throws Exception {
        InvestorLogin investor = seedActiveInvestorWithLogin();
        String bearer = bearerFor(investor.login());

        JsonNode body = node(mvc.perform(get("/auth/session").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn());

        assertThat(body.get("kind").asText()).isEqualTo("investor");
        assertThat(body.get("roles")).isEmpty();
        assertThat(body.get("investor_id").asText()).isEqualTo(investor.investorId().toString());
    }

    @Test
    void admin_bearer_session_has_null_investor_id() throws Exception {
        String bearer = bearerFor(seedAdminWithRoles("ops_executive"));

        JsonNode body = node(mvc.perform(get("/auth/session").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn());

        assertThat(body.get("investor_id").isNull()).isTrue();
    }

    @Test
    void investor_marketplace_returns_all_live_only() throws Exception {
        InvestorLogin investor = seedActiveInvestorWithLogin();
        String bearer = bearerFor(investor.login());
        UUID live1 = seedListing("live").listingId();
        UUID live2 = seedListing("live").listingId();
        UUID notLive = seedListing("fully_funded").listingId();

        // Even with an explicit non-live status param, the investor sees only live listings.
        List<JsonNode> rows = rows(mvc.perform(get("/listings").param("status", "fully_funded")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn());

        List<String> ids = new ArrayList<>();
        rows.forEach(r -> ids.add(r.get("listing_id").asText()));
        assertThat(ids).contains(live1.toString(), live2.toString());
        assertThat(ids).doesNotContain(notLive.toString());
    }

    @Test
    void admin_marketplace_unscoped() throws Exception {
        String bearer = bearerFor(seedAdminWithRoles("ops_executive"));
        UUID notLive = seedListing("fully_funded").listingId();

        List<JsonNode> rows = rows(mvc.perform(get("/listings").param("status", "fully_funded")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn());

        List<String> ids = new ArrayList<>();
        rows.forEach(r -> ids.add(r.get("listing_id").asText()));
        assertThat(ids).contains(notLive.toString());
    }

    @Test
    void investor_reads_own_portfolio_with_summary() throws Exception {
        InvestorLogin investor = seedActiveInvestorWithLogin();
        String bearer = bearerFor(investor.login());
        ListingFixture listing = seedListing("live");
        ListingFixture maturedListing = seedListing("distributed");
        UUID activeSub = seedSubscription(investor.investorId(), listing.listingId(), 20_00_000_00L, "committed");
        UUID maturedSub = seedSubscription(investor.investorId(), maturedListing.listingId(), 15_00_000_00L, "closed");
        jdbc.update("UPDATE sub_subscription SET distribution_outcome = "
                        + "'{\"gross\":16000000,\"tds\":1000000,\"fee\":500000,\"net\":14500000}'::jsonb "
                        + "WHERE subscription_id = ?",
                maturedSub);

        JsonNode body = node(mvc.perform(get("/investors/{id}/subscriptions", investor.investorId())
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn());

        List<JsonNode> rows = new ArrayList<>();
        body.get("rows").forEach(rows::add);
        assertThat(rows).hasSize(2);
        JsonNode active = rowFor(rows, activeSub);
        assertThat(active.get("subscription_id").asText()).isEqualTo(activeSub.toString());
        assertThat(active.get("listing_id").asText()).isEqualTo(listing.listingId().toString());
        assertThat(active.get("amount").asLong()).isEqualTo(20_00_000_00L);
        assertThat(active.get("status").asText()).isEqualTo("committed");
        assertThat(active.get("buyer_name").asText()).isEqualTo(listing.buyerName());
        assertThat(active.get("supplier_name").asText()).isEqualTo(listing.supplierName());
        assertThat(active.get("due_date").asText()).isEqualTo("2026-03-01");
        assertThat(active.get("distribution_outcome").isNull()).isTrue();
        assertThat(active.has("wallet_attribution")).isFalse();

        JsonNode matured = rowFor(rows, maturedSub);
        assertThat(matured.get("distribution_outcome").get("net").asLong()).isEqualTo(1_45_00_000L);
        assertThat(matured.has("wallet_attribution")).isFalse();

        JsonNode summary = body.get("summary");
        assertThat(summary.get("total_deployed_paise").asLong()).isEqualTo(20_00_000_00L); // matured excluded (closed)
        assertThat(summary.get("total_returned_paise").asLong()).isEqualTo(1_45_00_000L);
        assertThat(summary.get("active_positions").asInt()).isEqualTo(1);
        assertThat(summary.get("matured_positions").asInt()).isEqualTo(1);
    }

    @Test
    void investor_cannot_read_another_investors_portfolio() throws Exception {
        InvestorLogin investorA = seedActiveInvestorWithLogin();
        InvestorLogin investorB = seedActiveInvestorWithLogin();
        String bearerA = bearerFor(investorA.login());

        mvc.perform(get("/investors/{id}/subscriptions", investorB.investorId())
                        .header("Authorization", "Bearer " + bearerA))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_reads_any_portfolio() throws Exception {
        InvestorLogin investor = seedActiveInvestorWithLogin();
        String adminBearer = bearerFor(seedAdminWithRoles("ops_executive"));

        mvc.perform(get("/investors/{id}/subscriptions", investor.investorId())
                        .header("Authorization", "Bearer " + adminBearer))
                .andExpect(status().isOk());
    }

    /**
     * OWN-1 regression: the un-scoped portfolio view is reserved for admins by a <b>positive</b> check,
     * never granted by default to "caller has no inv_account". An authenticated non-investor, non-admin
     * kind (e.g. an ack-user — no {@code inv_account}, no {@code admin_user} row) must not inherit
     * unscoped access to an arbitrary investor's portfolio.
     */
    @Test
    void a_non_investor_non_admin_bearer_cannot_read_any_portfolio() throws Exception {
        InvestorLogin investor = seedActiveInvestorWithLogin();
        String otherKindBearer = bearerFor(seedLoginIdentity("acknowledgment_user"));

        mvc.perform(get("/investors/{id}/subscriptions", investor.investorId())
                        .header("Authorization", "Bearer " + otherKindBearer))
                .andExpect(status().isForbidden());
    }

    // RO-1's old "investor cannot commit at all" lock was retired by BE-18 (M11-B): self-commit is now the
    // one permitted investor write. Its coverage (happy path, cross-tenant reject, eligibility, idempotency)
    // lives in subscription/InvestorSelfCommitTest. The onboarding-command lock below is RO-1's residual proof.

    @Test
    void investor_bearer_cannot_run_onboarding_command() throws Exception {
        InvestorLogin investor = seedActiveInvestorWithLogin();
        String bearer = bearerFor(investor.login());

        mvc.perform(post("/investors/{id}/submit-kyc", UUID.randomUUID())
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .header("X-Aggregate-Version", "0"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));
    }

    // --- helpers ----------------------------------------------------------------------------------------

    private List<JsonNode> rows(MvcResult res) {
        List<JsonNode> out = new ArrayList<>();
        node(res).forEach(out::add);
        return out;
    }

    private JsonNode rowFor(List<JsonNode> rows, UUID subscriptionId) {
        return rows.stream()
                .filter(r -> r.get("subscription_id").asText().equals(subscriptionId.toString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for subscription " + subscriptionId));
    }
}
