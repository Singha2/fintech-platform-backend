package com.arthvritt.platform.dev;

import com.arthvritt.platform.shared.Ids;
import com.arthvritt.platform.shared.error.NotFoundException;
import com.arthvritt.platform.shared.error.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <b>Dev profile only</b> ({@code --spring.profiles.active=dev}) — fast-forwards a <b>fresh</b> listing to a
 * requested money-flow {@code stage} via direct {@link JdbcTemplate} inserts, so the UI's write commands
 * (S12 subscribe, S6 disburse, S7 mature/distribute) can be exercised end-to-end <b>without</b> hand-driving
 * the ~20-command real pipeline (create → ops-checks + BC16 document upload → buyer-ack → snapshot → go-live →
 * subscribe → funding → assignment-signing → disbursement → maturity → distribution). Sibling of
 * {@link DevDataSeeder}; invoked from {@code POST /dev/seed-listing} in {@link DevController}.
 *
 * <p><b>This deliberately bypasses maker-checker / MFA / ops-checks / document gates</b> — the point is to
 * LAND the terminal DB state fast, not to re-run the flow. That is exactly why it is {@link Profile}-guarded to
 * dev and never wired in prod. It <b>only inserts state</b>; it changes no real command, service, read or
 * migration. Each call mints fresh ids (no collisions) and reuses {@link DevDataSeeder}'s active
 * supplier/buyer/investor.
 *
 * <p>The seeded state is <b>valid</b> — it satisfies the DB CHECK constraints and the invariants the
 * command-under-test reads. In particular for {@code disbursable} the listing is exactly {@code fully_funded}
 * ∧ {@code all_signed} with a {@code drafted} disbursement whose {@code maker_id} ≠ the treasury account that
 * will approve it, so S6 approve (checker ≠ maker) flips it {@code fully_funded → disbursed}.
 *
 * <p>The tax split for {@code matured} is computed inline because the dev dataset has exactly one investor per
 * listing ({@code sub_subscription} is unique per (listing, investor)) — a single-leg distribution whose
 * {@code gross = face_value}, {@code net = face_value − tds}. It mirrors what {@code DistributionService.draft}
 * would write (immutable {@code tds_snapshot} payload + a stamped {@code tax_year_profile}) so S7 distribution
 * approve reads a consistent frozen snapshot and closes the deal.
 */
@Component
@Profile("dev")
public class DevListingSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevListingSeeder.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Defaults (all overridable via the request body). */
    private static final long DEFAULT_FACE_PAISE = 50_00_000L;   // ₹50,000 face value
    private static final long MIN_TICKET_PAISE = 1_000_000L;     // ₹10,000 min subscription ticket (S.1, DL-007)
    private static final int DEFAULT_RATE_BPS = 1200;            // 12% discount rate (within the seeded band)
    private static final int TENOR_DAYS = 45;                    // 31_60d bucket (matches the seeded pricing band)
    private static final String DEFAULT_MAKER_EMAIL = "treasury@dev.local";

    private final JdbcTemplate jdbc;

    public DevListingSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The money-flow stages this helper can land, in spine order. */
    enum Stage {
        LIVE("live"),
        FULLY_FUNDED("fully_funded"),
        DISBURSABLE("fully_funded"),   // fully_funded + a drafted disbursement (ready for S6 approve)
        DISBURSED("disbursed"),
        MATURED("matured_payment_received");

        final String listingStatus;

        Stage(String listingStatus) {
            this.listingStatus = listingStatus;
        }

        static Stage parse(String raw) {
            String s = (raw == null || raw.isBlank()) ? "live" : raw.trim().toLowerCase();
            return switch (s) {
                case "live" -> LIVE;
                case "fully_funded" -> FULLY_FUNDED;
                case "disbursable" -> DISBURSABLE;
                case "disbursed" -> DISBURSED;
                case "matured" -> MATURED;
                default -> throw new ValidationException("unknown stage: " + raw
                        + " (expected: live | fully_funded | disbursable | disbursed | matured)");
            };
        }
    }

    /**
     * Seeds a fresh listing at {@code stage} and returns the ids the caller/UI acts on. Transactional: on any
     * constraint failure it rolls back and fails loud (dev only) — never a half-seeded listing.
     */
    @Transactional
    public Map<String, Object> seed(String stageRaw, Integer rateBpsIn, Long amountPaiseIn, String makerEmailIn) {
        Stage stage = Stage.parse(stageRaw);
        long faceValue = amountPaiseIn != null ? amountPaiseIn : DEFAULT_FACE_PAISE;
        int rateBps = rateBpsIn != null ? rateBpsIn : DEFAULT_RATE_BPS;
        String makerEmail = (makerEmailIn == null || makerEmailIn.isBlank()) ? DEFAULT_MAKER_EMAIL : makerEmailIn.trim();
        if (faceValue <= 0) {
            throw new ValidationException("amount_paise (face value) must be positive");
        }

        Parties parties = resolveParties();
        long fundingTarget = computeFundingTarget(faceValue, rateBps, parties.feeBps());
        // funding_target must clear the ₹10,000 min ticket (S.1): a funded stage subscribes exactly
        // funding_target (which would else break CHECK sub_subscription_min_amount), and a `live` stage is
        // only subscribable if a ≥ min-ticket amount fits under funding_target. Guard both with one check.
        if (fundingTarget < MIN_TICKET_PAISE) {
            throw new ValidationException("computed funding_target " + fundingTarget + " is below the ₹10,000 min "
                    + "ticket for face=" + faceValue + ", rate_bps=" + rateBps + ", fee_bps=" + parties.feeBps()
                    + " — raise amount_paise");
        }

        UUID invoiceId = insertInvoice(parties, faceValue);
        UUID vaId = Ids.newId();
        boolean funded = stage != Stage.LIVE;
        long committed = funded ? fundingTarget : 0L;
        UUID listingId = insertListing(invoiceId, parties, stage, fundingTarget, committed, vaId, rateBps);
        insertVirtualAccount(vaId, listingId, committed);

        UUID subscriptionId = funded ? insertConfirmedSubscription(listingId, parties.investorId(), fundingTarget) : null;

        UUID makerAdminId = resolveAdminId(makerEmail);
        UUID payoutInstructionId = null;
        switch (stage) {
            case DISBURSABLE -> payoutInstructionId = insertDisbursement(listingId, fundingTarget, makerAdminId, false);
            case DISBURSED -> payoutInstructionId = insertDisbursement(listingId, fundingTarget, makerAdminId, true);
            case MATURED -> {
                insertDisbursement(listingId, fundingTarget, makerAdminId, true); // historical, executed
                payoutInstructionId = insertDistributionDraft(listingId, parties.investorId(), subscriptionId,
                        faceValue, fundingTarget, makerAdminId);
            }
            default -> { /* live / fully_funded need no payout instruction */ }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("listing_id", listingId.toString());
        out.put("invoice_id", invoiceId.toString());
        out.put("supplier_id", parties.supplierId().toString());
        out.put("buyer_id", parties.buyerId().toString());
        out.put("investor_id", parties.investorId().toString());
        out.put("subscription_id", subscriptionId == null ? null : subscriptionId.toString());
        out.put("payout_instruction_id", payoutInstructionId == null ? null : payoutInstructionId.toString());
        out.put("stage", stageRaw == null || stageRaw.isBlank() ? "live" : stageRaw.trim().toLowerCase());
        out.put("funding_target", fundingTarget);
        out.put("status", stage.listingStatus);
        log.info("[dev-seed-listing] stage={} listing={} invoice={} subscription={} payout={} funding_target={}",
                out.get("stage"), listingId, invoiceId, subscriptionId, payoutInstructionId, fundingTarget);
        return out;
    }

    // --- inserts -----------------------------------------------------------------------------------

    private UUID insertInvoice(Parties parties, long faceValue) {
        UUID invoiceId = Ids.newId();
        LocalDate invoiceDate = LocalDate.now(IST).minusDays(TENOR_DAYS);
        LocalDate dueDate = invoiceDate.plusDays(TENOR_DAYS); // INV.4: due_date = invoice_date + tenor_days
        // Fresh, unique invoice_number each call → no clash with the manual-entry partial unique index
        // (irn NULL) on (supplier, buyer, invoice_number, face_value, tenor). The FULL id is used, not a
        // prefix: UUIDv7 shares a time-ordered high prefix within a run, so a short slice would collide.
        String invoiceNumber = "DEV-INV-" + invoiceId;
        // check_outcomes '{}' — no downstream command re-runs ops-checks against a directly-seeded terminal state.
        jdbc.update("INSERT INTO deal_invoice (invoice_id, supplier_id, buyer_id, irn, invoice_number, face_value, "
                        + "invoice_date, tenor_days, due_date, status, check_outcomes) "
                        + "VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, 'listed'::deal_invoice_status, '{}'::jsonb)",
                invoiceId, parties.supplierId(), parties.buyerId(), invoiceNumber, faceValue,
                invoiceDate, TENOR_DAYS, dueDate);
        return invoiceId;
    }

    private UUID insertListing(UUID invoiceId, Parties parties, Stage stage, long fundingTarget, long committed,
                               UUID vaId, int rateBps) {
        UUID listingId = Ids.newId();
        boolean allSigned = stage != Stage.LIVE; // C27: disbursement needs fully_funded ∧ all_signed
        // pricing_snapshot is immutable after ready_for_review (L.3); all seeded stages are past it.
        String pricingSnapshot = String.format(
                "{\"pricing_band_id\":\"%s\",\"rate_bps\":%d,\"fee_bps\":%d,\"snapshot_at\":\"%s\"}",
                parties.pricingBandId(), rateBps, parties.feeBps(), LocalDate.now(IST));
        jdbc.update("INSERT INTO deal_listing (listing_id, invoice_id, supplier_id, buyer_id, status, "
                        + "pricing_snapshot, funding_target, committed_total, va_id, all_signed) "
                        + "VALUES (?, ?, ?, ?, ?::deal_listing_status, ?::jsonb, ?, ?, ?, ?)",
                listingId, invoiceId, parties.supplierId(), parties.buyerId(), stage.listingStatus,
                pricingSnapshot, fundingTarget, committed, vaId, allSigned);
        return listingId;
    }

    private void insertVirtualAccount(UUID vaId, UUID listingId, long expectedInflow) {
        jdbc.update("INSERT INTO cash_virtual_account (va_id, listing_id, status, expected_inflow_total) "
                        + "VALUES (?, ?, 'created'::cash_virtual_account_status, ?)",
                vaId, listingId, expectedInflow);
    }

    private UUID insertConfirmedSubscription(UUID listingId, UUID investorId, long amount) {
        UUID subscriptionId = Ids.newId();
        // 'confirmed' (funds received + reconciled) is what the assignment/disbursement/distribution reads assume.
        jdbc.update("INSERT INTO sub_subscription (subscription_id, listing_id, investor_id, amount, status, "
                        + "expected_inflow_amount, actual_inflow_txn_ref) "
                        + "VALUES (?, ?, ?, ?, 'confirmed'::sub_subscription_status, ?, 'DEV-SEED-INFLOW')",
                subscriptionId, listingId, investorId, amount, amount);
        return subscriptionId;
    }

    /**
     * A {@code kind='disbursement'} payout. {@code drafted} (maker only, checker null) is the state S6 approve
     * consumes; {@code executed} (with a checker ≠ maker) is the historical row for a listing already disbursed.
     * gross = net = funding_target, fee = 0 — the supplier is paid the discounted value (mirrors the real draft).
     */
    private UUID insertDisbursement(UUID listingId, long fundingTarget, UUID makerAdminId, boolean executed) {
        UUID payoutId = Ids.newId();
        if (executed) {
            UUID checkerAdminId = resolveOtherTreasury(makerAdminId);
            jdbc.update("INSERT INTO cash_payout_instruction (payout_instruction_id, kind, listing_id, status, "
                            + "gross_amount, net_amount, fee_amount, maker_id, checker_id, checker_mfa_assertion_id, "
                            + "instruction_sla_date) VALUES (?, 'disbursement'::cash_payout_kind, ?, "
                            + "'executed'::cash_payout_status, ?, ?, 0, ?, ?, 'dev-seed-mfa', now()::date + 1)",
                    payoutId, listingId, fundingTarget, fundingTarget, makerAdminId, checkerAdminId);
        } else {
            jdbc.update("INSERT INTO cash_payout_instruction (payout_instruction_id, kind, listing_id, status, "
                            + "gross_amount, net_amount, fee_amount, maker_id, instruction_sla_date) "
                            + "VALUES (?, 'disbursement'::cash_payout_kind, ?, 'drafted'::cash_payout_status, ?, ?, 0, "
                            + "?, now()::date + 1)",
                    payoutId, listingId, fundingTarget, fundingTarget, makerAdminId);
        }
        return payoutId;
    }

    /**
     * A {@code kind='distribution'} {@code drafted} payout for the single seeded investor, mirroring what
     * {@code DistributionService.draft} freezes: gross = face_value, one leg (interest = face − funding_target,
     * tds at the FY default rate, net = gross − tds), plus a stamped {@code tax_year_profile} so the checker's
     * FY-cumulative bump (rowcount-guarded) succeeds on approve.
     */
    private UUID insertDistributionDraft(UUID listingId, UUID investorId, UUID subscriptionId, long faceValue,
                                         long fundingTarget, UUID makerAdminId) {
        long interest = faceValue - fundingTarget; // total return for the single investor
        if (interest < 0) {
            throw new ValidationException("face_value below funding_target — not a distribution");
        }
        String fyCode = currentFyCode();
        int rateBps = defaultTdsRateBps(fyCode, false); // seeded investor has no PAN on file → 206AA band
        long tds = BigDecimal.valueOf(interest).multiply(BigDecimal.valueOf(rateBps))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_EVEN).longValueExact();
        long gross = faceValue;
        long net = gross - tds;
        if (net <= 0) {
            throw new ValidationException("computed net distribution is not positive — tds too high");
        }

        // Stamp the FY profile the approve path bumps (composite PK; idempotent across repeated matured seeds).
        jdbc.update("INSERT INTO tax_year_profile (investor_id, fy_code, tds_rate_bps, pan_verified) "
                        + "VALUES (?, ?, ?, FALSE) ON CONFLICT (investor_id, fy_code) DO NOTHING",
                investorId, fyCode, rateBps);

        String payload = String.format(
                "{\"gross\":%d,\"net\":%d,\"fee\":0,\"total_tds\":%d,\"fy_code\":\"%s\","
                        + "\"tds_snapshot\":[{\"subscription_id\":\"%s\",\"investor_id\":\"%s\",\"gross\":%d,"
                        + "\"interest\":%d,\"tds\":%d,\"fee\":0,\"net\":%d}]}",
                gross, net, tds, fyCode, subscriptionId, investorId, gross, interest, tds, net);

        UUID payoutId = Ids.newId();
        jdbc.update("INSERT INTO cash_payout_instruction (payout_instruction_id, kind, listing_id, status, "
                        + "gross_amount, net_amount, fee_amount, total_tds_amount, payload, maker_id, "
                        + "instruction_sla_date) VALUES (?, 'distribution'::cash_payout_kind, ?, "
                        + "'drafted'::cash_payout_status, ?, ?, 0, ?, ?::jsonb, ?, now()::date + 1)",
                payoutId, listingId, gross, net, tds, payload, makerAdminId);
        return payoutId;
    }

    // --- resolution helpers ------------------------------------------------------------------------

    private Parties resolveParties() {
        UUID supplierId = single("SELECT supplier_id FROM sup_account WHERE legal_name = 'DEV Supplier'",
                "seeded supplier (run DevDataSeeder / start with --spring.profiles.active=dev)");
        UUID buyerId = single("SELECT buyer_id FROM buyer_account WHERE legal_name = 'DEV Buyer'", "seeded buyer");
        UUID investorId = single(
                "SELECT investor_id FROM inv_account WHERE identity_id = "
                        + "(SELECT identity_id FROM auth_identity WHERE email = 'investor@dev.local')",
                "seeded investor");
        Map<String, Object> band = jdbc.queryForList(
                "SELECT pricing_band_id, fee_bps FROM risk_pricing_policy WHERE buyer_id = ? LIMIT 1", buyerId)
                .stream().findFirst()
                .orElseThrow(() -> new NotFoundException("no seeded pricing band for the DEV buyer"));
        UUID pricingBandId = (UUID) band.get("pricing_band_id");
        int feeBps = ((Number) band.get("fee_bps")).intValue();
        return new Parties(supplierId, buyerId, investorId, pricingBandId, feeBps);
    }

    private UUID resolveAdminId(String email) {
        return single("SELECT admin_user_id FROM admin_user WHERE email = '" + sqlLiteral(email) + "'",
                "admin account " + email);
    }

    /** Any active treasury admin that is not the maker — the checker for a seeded 'executed' disbursement. */
    private UUID resolveOtherTreasury(UUID makerAdminId) {
        UUID id = jdbc.query(
                "SELECT a.admin_user_id FROM admin_user a "
                        + "JOIN admin_role_assignment r ON r.admin_user_id = a.admin_user_id "
                        + "WHERE r.role = 'treasury_and_settlement'::admin_role AND r.status = 'active' "
                        + "AND a.admin_user_id <> ? LIMIT 1",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, makerAdminId);
        if (id == null) {
            throw new NotFoundException("need a second treasury admin to seed an executed disbursement");
        }
        return id;
    }

    private UUID single(String sql, String what) {
        UUID id = jdbc.query(sql, rs -> rs.next() ? rs.getObject(1, UUID.class) : null);
        if (id == null) {
            throw new NotFoundException("could not resolve " + what);
        }
        return id;
    }

    /** funding_target = face_value − discount − fee (L.7). discount = face × rate_bps/10000 × tenor/365. */
    private long computeFundingTarget(long faceValue, int rateBps, int feeBps) {
        long discount = BigDecimal.valueOf(faceValue)
                .multiply(BigDecimal.valueOf(rateBps)).multiply(BigDecimal.valueOf(TENOR_DAYS))
                .divide(BigDecimal.valueOf(10_000L * 365L), 0, RoundingMode.FLOOR).longValueExact();
        long fee = BigDecimal.valueOf(faceValue).multiply(BigDecimal.valueOf(feeBps))
                .divide(BigDecimal.valueOf(10_000L), 0, RoundingMode.FLOOR).longValueExact();
        return faceValue - discount - fee;
    }

    private int defaultTdsRateBps(String fyCode, boolean panVerified) {
        Integer rate = jdbc.query("SELECT rate_bps FROM tax_rate_default WHERE fy_code = ? AND pan_verified = ?",
                rs -> rs.next() ? rs.getInt(1) : null, fyCode, panVerified);
        if (rate == null) {
            throw new NotFoundException("no TDS default rate for " + fyCode + " (pan_verified=" + panVerified + ")");
        }
        return rate;
    }

    /** Indian FY code for today in IST — e.g. 2026-07-18 → FY2026-27 (mirrors tax.FinancialYear). */
    private static String currentFyCode() {
        LocalDate today = LocalDate.now(IST);
        int startYear = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
        return String.format("FY%d-%02d", startYear, (startYear + 1) % 100);
    }

    /** Escapes single quotes for the one inlined identifier (emails come from the request body). */
    private static String sqlLiteral(String value) {
        return value.replace("'", "''");
    }

    private record Parties(UUID supplierId, UUID buyerId, UUID investorId, UUID pricingBandId, int feeBps) {
    }
}
