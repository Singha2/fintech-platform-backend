package com.arthvritt.platform.web;

import com.arthvritt.platform.AbstractIntegrationTest;
import com.arthvritt.platform.auth.AuthService;
import com.arthvritt.platform.notification.StubNotifier;
import com.arthvritt.platform.shared.Ids;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared base for HTTP edge tests (WS-0 onward): MockMvc over the Testcontainers Postgres, plus the
 * admin-seed + HTTP-login helpers every slice's test needs to obtain a bearer. Seeding writes an active
 * admin with a password directly (test fixture); the bearer is then obtained over the <i>real</i> login
 * endpoints (password → SMS-OTP → session), so every test drives the same WS-0 edge an operator would.
 */
@AutoConfigureMockMvc
public abstract class AbstractEdgeHttpTest extends AbstractIntegrationTest {

    private static final Random RND = new Random();

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected AuthService auth;
    @Autowired protected StubNotifier notifier;
    @Autowired protected JdbcTemplate jdbc;

    /** A seeded active admin holding all {@code roles}, with a password set so it can log in over HTTP. */
    protected Seeded seedAdminWithRoles(String... roles) {
        String email = "adm-" + UUID.randomUUID() + "@arthvritt.test";
        String password = "Pw-" + UUID.randomUUID();
        UUID identityId = auth.provisionIdentity("admin_user", email, phone(), "Admin");
        UUID adminUserId = Ids.newId();
        jdbc.update("INSERT INTO admin_user (admin_user_id, identity_id, email, display_name, status) "
                + "VALUES (?, ?, ?, ?, 'active')", adminUserId, identityId, email, "Admin");
        for (String role : roles) {
            jdbc.update("INSERT INTO admin_role_assignment (admin_user_id, role, status, assigned_by) "
                    + "VALUES (?, ?::admin_role, 'active', ?)", adminUserId, role, adminUserId);
        }
        auth.setPassword(identityId, password);
        return new Seeded(adminUserId, identityId, email, password);
    }

    /**
     * A non-admin login identity (password + phone) of the given {@code kind} — enough to obtain a session
     * bearer. No {@code admin_user} row and no roles, so {@code /auth/session} reports {@code roles:[]}.
     */
    protected Seeded seedLoginIdentity(String kind) {
        String email = kind + "-" + UUID.randomUUID() + "@arthvritt.test";
        String password = "Pw-" + UUID.randomUUID();
        UUID identityId = auth.provisionIdentity(kind, email, phone(), kind);
        auth.setPassword(identityId, password);
        return new Seeded(Ids.newId(), identityId, email, password);   // adminUserId unused for login
    }

    /** Logs the seeded admin in over HTTP (password → verify-otp) and returns the session bearer. */
    protected String bearerFor(Seeded admin) {
        try {
            MvcResult pw = mvc.perform(post("/auth/login/password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("email", admin.email(), "password", admin.password()))))
                    .andExpect(status().isOk()).andReturn();
            String challengeId = node(pw).get("challenge_id").asText();
            String code = notifier.lastCodeFor(admin.identityId()).orElseThrow();
            MvcResult otp = mvc.perform(post("/auth/login/verify-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("challenge_id", challengeId, "code", code))))
                    .andExpect(status().isOk()).andReturn();
            return node(otp).get("bearer").asText();
        } catch (Exception e) {
            throw new IllegalStateException("login failed for " + admin.email(), e);
        }
    }

    protected JsonNode node(MvcResult res) {
        try {
            return json.readTree(res.getResponse().getContentAsString());
        } catch (Exception e) {
            throw new IllegalStateException("unreadable response body", e);
        }
    }

    protected static String phone() {
        return "+9198" + (10_000_000 + RND.nextInt(89_999_999));
    }

    /**
     * M10-D T1 — bridges the harness gap: {@link #seedLoginIdentity} gives a bearer but no
     * {@code inv_account}; a per-test {@code seedActiveInvestor} gives an {@code inv_account} but no
     * password. This gives both: an {@code auth_identity} (kind {@code investor}, active) with a password
     * credential, an {@code inv_invite}, and an {@code inv_account} at the given status (default
     * {@code active}). {@code inv_invite.issued_by} needs a real {@code admin_user} FK, so a throwaway
     * admin is seeded for that purpose only.
     */
    protected InvestorLogin seedActiveInvestorWithLogin() {
        return seedActiveInvestorWithLogin("active");
    }

    protected InvestorLogin seedActiveInvestorWithLogin(String invAccountStatus) {
        UUID issuedBy = seedAdminWithRoles().adminUserId();   // inv_invite.issued_by FK -> admin_user
        String email = "invsl-" + UUID.randomUUID() + "@arthvritt.test";
        String password = "Pw-" + UUID.randomUUID();
        UUID identityId = auth.provisionIdentity("investor", email, phone(), "Investor");
        UUID inviteId = Ids.newId();
        UUID investorId = Ids.newId();
        jdbc.update("INSERT INTO inv_invite (invite_id, email_hash, phone_hash, issued_by, expiry_at, status) "
                        + "VALUES (?, ?, ?, ?, now() + interval '14 days', 'pending')",
                inviteId, email.getBytes(StandardCharsets.UTF_8), phone().getBytes(StandardCharsets.UTF_8), issuedBy);
        jdbc.update("INSERT INTO inv_account (investor_id, identity_id, invite_id, sub_type, status) "
                        + "VALUES (?, ?, ?, 'resident_individual'::inv_sub_type, ?::inv_account_status)",
                investorId, identityId, inviteId, invAccountStatus);
        auth.setPassword(identityId, password);
        return new InvestorLogin(investorId, new Seeded(Ids.newId(), identityId, email, password));
    }

    /**
     * A supplier/buyer-backed listing at the given status — real counterparties (not bare UUIDs), so
     * {@code buyer_name}/{@code supplier_name} joins resolve (mirrors {@code ListingReadTest}/
     * {@code ListingDetailTest}'s seeding). M10-D T1.
     */
    protected ListingFixture seedListing(String status) {
        UUID admin = seedAdminWithRoles().adminUserId();   // buyer_account.nominated_by FK -> admin_user
        UUID supplierId = Ids.newId();
        String supplierName = "Supplier " + UUID.randomUUID();
        jdbc.update("INSERT INTO sup_account (supplier_id, legal_name, constitution_type, pan, status) "
                        + "VALUES (?, ?, 'private_limited'::sup_constitution_type, 'AAAAA1111A', "
                        + "'active'::sup_account_status)",
                supplierId, supplierName);
        UUID buyerId = Ids.newId();
        String buyerName = "Buyer " + UUID.randomUUID();
        jdbc.update("INSERT INTO buyer_account (buyer_id, legal_name, status, nominated_by) "
                        + "VALUES (?, ?, 'active'::buyer_account_status, ?)", buyerId, buyerName, admin);
        UUID invoiceId = Ids.newId();
        jdbc.update("INSERT INTO deal_invoice (invoice_id, supplier_id, buyer_id, invoice_number, face_value, "
                        + "invoice_date, tenor_days, due_date) "
                        + "VALUES (?, ?, ?, ?, ?, '2026-01-15', 45, '2026-03-01')",
                invoiceId, supplierId, buyerId, "INV-" + UUID.randomUUID(), 10_00_000_00L);
        UUID listingId = Ids.newId();
        jdbc.update("INSERT INTO deal_listing (listing_id, invoice_id, supplier_id, buyer_id, status) "
                        + "VALUES (?, ?, ?, ?, ?::deal_listing_status)",
                listingId, invoiceId, supplierId, buyerId, status);
        return new ListingFixture(listingId, invoiceId, supplierId, buyerId, supplierName, buyerName);
    }

    /** A {@code live} listing (shorthand over {@link #seedListing(String)} — the common marketplace case). */
    protected UUID seedLiveListing() {
        return seedListing("live").listingId();
    }

    /**
     * A {@code sub_subscription} row (M10-D T1) — {@code expected_inflow_amount} mirrors {@code amountPaise}
     * (the S.3 baseline before any inflow reconciliation).
     */
    protected UUID seedSubscription(UUID investorId, UUID listingId, long amountPaise, String status) {
        UUID id = Ids.newId();
        jdbc.update("INSERT INTO sub_subscription (subscription_id, listing_id, investor_id, amount, status, "
                        + "expected_inflow_amount) VALUES (?, ?, ?, ?, ?::sub_subscription_status, ?)",
                id, listingId, investorId, amountPaise, status, amountPaise);
        return id;
    }

    /** A seeded admin: its admin_user id, auth identity id, and login credentials. */
    protected record Seeded(UUID adminUserId, UUID identityId, String email, String password) {
    }

    /** An investor with a login (M10-D T1): its resolved {@code investor_id} + the bearer-capable {@link Seeded}. */
    protected record InvestorLogin(UUID investorId, Seeded login) {
    }

    /** A seeded listing over real supplier/buyer counterparties (M10-D T1) — names available for join assertions. */
    protected record ListingFixture(UUID listingId, UUID invoiceId, UUID supplierId, UUID buyerId,
                                    String supplierName, String buyerName) {
    }
}
