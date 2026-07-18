package com.arthvritt.platform.dev;

import com.arthvritt.platform.auth.AuthService;
import com.arthvritt.platform.shared.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * <b>Dev profile only</b> ({@code --spring.profiles.active=dev}) — seeds a ready-to-use local dataset on
 * startup so an operator can log in and drive the API by hand without any SQL. Idempotent: it does nothing
 * if {@code admin_user} already has rows. Never wired in prod (the {@link Profile} guard).
 *
 * <p>Seeds: seven admins (one per role + a second Treasury for the disbursement maker-checker pair + a
 * second Ops for the DOC.3 / two-ops maker-checker pair), all with
 * password {@code DevPass123!}; and one active supplier, buyer (+ active acknowledgment user), investor, and
 * a pricing band — so a listing can go live → subscribe → assign → disburse → mature immediately. The investor
 * ({@code investor@dev.local}) also gets the same dev password (M10-D A1) so it can self-login read-only.
 * The seeded counterparty ids are exposed by {@code GET /dev/seed-info}.
 */
@Component
@Profile("dev")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);
    public static final String PASSWORD = "DevPass123!";
    private static final String PHONE = "+919800000000";

    private final JdbcTemplate jdbc;
    private final AuthService auth;

    public DevDataSeeder(JdbcTemplate jdbc, AuthService auth) {
        this.jdbc = jdbc;
        this.auth = auth;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Exclude the reserved SYSTEM principal (seeded by migration V7 to author the genesis SoD policy,
        // DL-BE-064) — it is always present, so counting it would make this guard permanently skip the seed.
        Integer admins = jdbc.queryForObject(
                "SELECT count(*) FROM admin_user WHERE admin_user_id <> '00000000-0000-0000-0000-000000000002'",
                Integer.class);
        if (admins != null && admins > 0) {
            log.info("[dev-seed] admin_user already populated ({} real rows) — skipping dev seed", admins);
            return;
        }
        log.info("[dev-seed] seeding dev admins + counterparties (password for every admin: {})", PASSWORD);

        UUID superAdmin = seedAdmin("super@dev.local", "Dev Super", "super_admin");
        seedAdmin("ops@dev.local", "Dev Ops", "ops_executive");
        // A second Ops so the DOC.3 (document-completeness) maker-checker — and any two-ops flow — is drivable
        // via real commands (proposer ≠ approver needs two ops_executive principals).
        seedAdmin("ops2@dev.local", "Dev Ops 2", "ops_executive");
        seedAdmin("treasury@dev.local", "Dev Treasury", "treasury_and_settlement");
        seedAdmin("treasury2@dev.local", "Dev Treasury 2", "treasury_and_settlement");
        seedAdmin("compliance@dev.local", "Dev Compliance", "compliance_reviewer");
        seedAdmin("credit@dev.local", "Dev Credit", "credit_reviewer");

        UUID supplierId = seedActiveSupplier();
        UUID buyerId = seedActiveBuyer(superAdmin);
        seedActiveAckUser(buyerId, superAdmin);
        seedPricingBand(buyerId);          // covers tenor bucket 31_60d, rate [1000,1500], fee 200 bps
        UUID investorId = seedActiveInvestor(superAdmin);

        log.info("[dev-seed] done. supplier={} buyer={} investor={} (GET /api/v1/dev/seed-info to fetch these)",
                supplierId, buyerId, investorId);
        log.info("[dev-seed] all routes are under /api/v1; health is on :8081/actuator/health");
        log.info("[dev-seed] log in: POST /api/v1/auth/login/password {{\"email\":\"ops@dev.local\",\"password\":\"{}\"}} "
                + "→ GET /api/v1/dev/last-otp?email=ops@dev.local → POST /api/v1/auth/login/verify-otp", PASSWORD);
    }

    private UUID seedAdmin(String email, String displayName, String role) {
        UUID identityId = auth.provisionIdentity("admin_user", email, PHONE, displayName);
        UUID adminUserId = Ids.newId();
        jdbc.update("INSERT INTO admin_user (admin_user_id, identity_id, email, display_name, status) "
                + "VALUES (?, ?, ?, ?, 'active')", adminUserId, identityId, email, displayName);
        jdbc.update("INSERT INTO admin_role_assignment (admin_user_id, role, status, assigned_by) "
                + "VALUES (?, ?::admin_role, 'active', ?)", adminUserId, role, adminUserId);
        auth.setPassword(identityId, PASSWORD);
        return adminUserId;
    }

    private UUID seedActiveSupplier() {
        UUID id = Ids.newId();
        jdbc.update("INSERT INTO sup_account (supplier_id, legal_name, constitution_type, pan, status, "
                        + "credit_exposure_cap_paise) VALUES (?, 'DEV Supplier', 'private_limited', "
                        + "?::pan_type, 'active', ?)", id, "ABCDE1234F", 5_00_00_000_00L);
        return id;
    }

    private UUID seedActiveBuyer(UUID nominatedBy) {
        UUID id = Ids.newId();
        jdbc.update("INSERT INTO buyer_account (buyer_id, legal_name, status, credit_limit_paise, nominated_by) "
                + "VALUES (?, 'DEV Buyer', 'active', ?, ?)", id, 5_00_00_000_00L, nominatedBy);
        return id;
    }

    private void seedActiveAckUser(UUID buyerId, UUID designatedBy) {
        UUID identityId = Ids.newId();
        String email = "ack@dev.local";
        jdbc.update("INSERT INTO auth_identity (identity_id, kind, email, phone_e164, display_name, status) "
                        + "VALUES (?, 'acknowledgment_user'::identity_kind_enum, ?, ?, 'Dev Ack User', "
                        + "'active'::identity_status_enum)", identityId, email, PHONE);
        jdbc.update("INSERT INTO buyer_ack_user "
                        + "(ack_user_id, buyer_id, identity_id, display_name, email, phone, is_active, designated_by) "
                        + "VALUES (?, ?, ?, 'Dev Ack User', ?, ?, TRUE, ?)",
                Ids.newId(), buyerId, identityId, email, PHONE, designatedBy);
    }

    private void seedPricingBand(UUID buyerId) {
        jdbc.update("INSERT INTO risk_pricing_policy (pricing_band_id, buyer_id, tenor_bucket, "
                        + "rate_range_min_bps, rate_range_max_bps, fee_bps, effective_from) "
                        + "VALUES (?, ?, '31_60d'::risk_tenor_bucket, 1000, 1500, 200, now()::date)",
                Ids.newId(), buyerId);
    }

    private UUID seedActiveInvestor(UUID issuedBy) {
        UUID identityId = Ids.newId();
        UUID inviteId = Ids.newId();
        UUID investorId = Ids.newId();
        String email = "investor@dev.local";
        jdbc.update("INSERT INTO auth_identity (identity_id, kind, email, phone_e164, display_name, status) "
                        + "VALUES (?, 'investor'::identity_kind_enum, ?, ?, 'Dev Investor', "
                        + "'active'::identity_status_enum)", identityId, email, PHONE);
        jdbc.update("INSERT INTO inv_invite (invite_id, email_hash, phone_hash, issued_by, expiry_at, status) "
                        + "VALUES (?, ?, ?, ?, now() + interval '14 days', 'pending')",
                inviteId, email.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "p".getBytes(java.nio.charset.StandardCharsets.UTF_8), issuedBy);
        jdbc.update("INSERT INTO inv_account (investor_id, identity_id, invite_id, sub_type, status) "
                        + "VALUES (?, ?, ?, 'resident_individual'::inv_sub_type, 'active'::inv_account_status)",
                investorId, identityId, inviteId);
        auth.setPassword(identityId, PASSWORD); // M10-D A1: investor@dev.local can now log in (dev/pilot only)
        return investorId;
    }
}
