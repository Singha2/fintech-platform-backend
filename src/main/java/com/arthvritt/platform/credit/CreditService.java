package com.arthvritt.platform.credit;

import com.arthvritt.platform.adminiam.AdminRole;
import com.arthvritt.platform.command.CommandEvent;
import com.arthvritt.platform.command.CommandGateway;
import com.arthvritt.platform.command.CommandOutcome;
import com.arthvritt.platform.command.CommandRejectedException;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.shared.error.NotFoundException;
import com.arthvritt.platform.shared.error.ValidationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BC3 Credit &amp; Underwriting — the write side (M6). The Credit Reviewer's policy commands the M9-A read
 * ports consume: {@code setPricingBand} (over {@code risk_pricing_policy}; the genuine gap — the bands were
 * only test-seeded) and the buyer/supplier credit profiles (M6-B). All through the {@link CommandGateway}.
 *
 * <p><b>Four-eyes (BCP.2/SCP.2) is deferred</b> (DL-BE-059): the ₹10 Cr threshold is DB-enforced, so M6
 * supports limits/caps ≤ ₹10 Cr and rejects above. Pricing-band <b>re-pricing/supersession (PB.3)</b> is
 * deferred too — the partial-UNIQUE + self-FK need a V7 deferrable-index migration; M6 creates a band and
 * rejects a re-price.
 */
@Service
public class CreditService {

    private static final String CONTEXT = "credit";
    private static final Set<String> CREDIT = Set.of(AdminRole.CREDIT_REVIEWER.wire());
    private static final Set<String> TENOR_BUCKETS = Set.of("lte_30d", "31_60d", "61_90d", "91_180d");
    /** ₹10 Cr in paise — above this, BCP.2/SCP.2 require four-eyes (deferred → rejected). DB CHECK backs it. */
    private static final long FOUR_EYES_THRESHOLD_PAISE = 10_000_000_000L;

    private final JdbcTemplate jdbc;
    private final CommandGateway gateway;

    public CreditService(JdbcTemplate jdbc, CommandGateway gateway) {
        this.jdbc = jdbc;
        this.gateway = gateway;
    }

    public CommandResult<UUID> setPricingBand(CommandRequest request, UUID buyerId, String tenorBucket,
                                              int minBps, int maxBps, int feeBps, String effectiveFrom) {
        return gateway.execute(request, CREDIT, () -> {
            if (!TENOR_BUCKETS.contains(tenorBucket)) {
                throw new ValidationException("tenor_bucket must be one of " + TENOR_BUCKETS);
            }
            // PB.1 + the bps_type domain ceiling (0..100000) — guard the upper bound too, else a clean 4xx
            // becomes a 500 at the DB CHECK.
            if (minBps <= 0 || minBps > maxBps || feeBps < 0 || maxBps > 100_000 || feeBps > 100_000) {
                throw new ValidationException("invalid pricing: require 0 < rate_min_bps <= rate_max_bps <= 100000 "
                        + "and 0 <= fee_bps <= 100000");
            }
            UUID bandId = request.aggregateId();
            try {
                jdbc.update("INSERT INTO risk_pricing_policy (pricing_band_id, buyer_id, tenor_bucket, "
                                + "rate_range_min_bps, rate_range_max_bps, fee_bps, effective_from) "
                                + "VALUES (?, ?, ?::risk_tenor_bucket, ?, ?, ?, COALESCE(?::date, now()::date))",
                        bandId, buyerId, tenorBucket, minBps, maxBps, feeBps, effectiveFrom);
            } catch (DuplicateKeyException e) { // PB.2: one active band per (buyer_id, tenor_bucket)
                throw new ValidationException("an active pricing band already exists for this buyer/tenor; "
                        + "re-pricing (supersession) is deferred (PB.3): " + buyerId);
            }
            CommandEvent event = new CommandEvent(CONTEXT + ".PricingBand.Set", 1,
                    Map.of("pricing_band_id", bandId.toString(), "buyer_id", buyerId.toString(),
                            "tenor_bucket", tenorBucket, "rate_min_bps", minBps, "rate_max_bps", maxBps,
                            "fee_bps", feeBps),
                    Map.of(), Map.of("status", "active"), true);
            return new CommandOutcome<>(bandId, event);
        });
    }

    /**
     * Upserts the buyer's BC3 credit profile ({@code risk_buyer_profile}, BCP.1) and snapshots the limit to
     * {@code buyer_account.credit_limit_paise} — the column the M9-A query port reads (inline; no bus). A
     * limit &gt; ₹10 Cr is rejected (BCP.2 four-eyes deferred). Credit Reviewer.
     */
    public CommandResult<Void> setBuyerCreditProfile(CommandRequest request, UUID buyerId, String sector,
                                                     String ratingSource, String rating, long creditLimitPaise,
                                                     int tenorCapDays) {
        return gateway.execute(request, CREDIT, () -> {
            if (creditLimitPaise <= 0) { // BCP.1
                throw new ValidationException("credit_limit must be > 0");
            }
            if (tenorCapDays < 1 || tenorCapDays > 180) { // BCP.1
                throw new ValidationException("tenor_cap_days must be in [1, 180]");
            }
            if (creditLimitPaise > FOUR_EYES_THRESHOLD_PAISE) { // BCP.2 — deferred
                throw CommandRejectedException.fourEyesRequired("buyer credit limit over ₹10 Cr");
            }
            requireExists("buyer_account", "buyer_id", buyerId);
            jdbc.update("INSERT INTO risk_buyer_profile "
                            + "(buyer_id, sector, rating_source, rating, credit_limit, tenor_cap_days) "
                            + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (buyer_id) DO UPDATE SET "
                            + "sector = EXCLUDED.sector, rating_source = EXCLUDED.rating_source, "
                            + "rating = EXCLUDED.rating, credit_limit = EXCLUDED.credit_limit, "
                            + "tenor_cap_days = EXCLUDED.tenor_cap_days",
                    buyerId, sector, ratingSource, rating, creditLimitPaise, tenorCapDays);
            // Snapshot to the BC9 column the M9-A read uses; assert it landed (the buyer can't vanish mid-tx).
            int snapshot = jdbc.update("UPDATE buyer_account SET credit_limit_paise = ? WHERE buyer_id = ?",
                    creditLimitPaise, buyerId);
            if (snapshot != 1) {
                throw new NotFoundException("buyer_account not found: " + buyerId);
            }
            CommandEvent event = new CommandEvent(CONTEXT + ".BuyerCreditProfile.Set", 1,
                    Map.of("buyer_id", buyerId.toString(), "credit_limit", creditLimitPaise), null, null, false);
            return new CommandOutcome<>(null, event);
        });
    }

    /**
     * Upserts the supplier's BC3 credit profile ({@code risk_supplier_profile}, SCP.1) and snapshots the cap
     * to {@code sup_account.credit_exposure_cap_paise} (the M9-A read). A cap &gt; ₹10 Cr is rejected (SCP.2
     * four-eyes deferred). Credit Reviewer.
     */
    public CommandResult<Void> setSupplierCreditProfile(CommandRequest request, UUID supplierId, String riskRating,
                                                        long exposureCapPaise) {
        return gateway.execute(request, CREDIT, () -> {
            if (exposureCapPaise < 0) { // SCP.1
                throw new ValidationException("exposure_cap must be >= 0");
            }
            if (exposureCapPaise > FOUR_EYES_THRESHOLD_PAISE) { // SCP.2 — deferred
                throw CommandRejectedException.fourEyesRequired("supplier exposure cap over ₹10 Cr");
            }
            requireExists("sup_account", "supplier_id", supplierId);
            jdbc.update("INSERT INTO risk_supplier_profile (supplier_id, risk_rating, exposure_cap) "
                            + "VALUES (?, ?, ?) ON CONFLICT (supplier_id) DO UPDATE SET "
                            + "risk_rating = EXCLUDED.risk_rating, exposure_cap = EXCLUDED.exposure_cap",
                    supplierId, riskRating, exposureCapPaise);
            int snapshot = jdbc.update("UPDATE sup_account SET credit_exposure_cap_paise = ? WHERE supplier_id = ?",
                    exposureCapPaise, supplierId);
            if (snapshot != 1) {
                throw new NotFoundException("sup_account not found: " + supplierId);
            }
            CommandEvent event = new CommandEvent(CONTEXT + ".SupplierCreditProfile.Set", 1,
                    Map.of("supplier_id", supplierId.toString(), "exposure_cap", exposureCapPaise), null, null, false);
            return new CommandOutcome<>(null, event);
        });
    }

    private void requireExists(String table, String idColumn, UUID id) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + idColumn + " = ?",
                Integer.class, id);
        if (n == null || n == 0) {
            throw new NotFoundException(table + " not found: " + id);
        }
    }
}
