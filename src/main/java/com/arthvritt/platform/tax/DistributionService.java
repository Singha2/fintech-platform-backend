package com.arthvritt.platform.tax;

import com.arthvritt.platform.adminiam.AdminRole;
import com.arthvritt.platform.adminiam.RoleResolver;
import com.arthvritt.platform.audit.Actor;
import com.arthvritt.platform.audit.AuditEnvelopes;
import com.arthvritt.platform.audit.AuditLog;
import com.arthvritt.platform.banking.EscrowPort;
import com.arthvritt.platform.banking.EscrowPort.PayoutLeg;
import com.arthvritt.platform.command.CommandEvent;
import com.arthvritt.platform.command.CommandGateway;
import com.arthvritt.platform.command.CommandOutcome;
import com.arthvritt.platform.command.CommandRejectedException;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.shared.error.NotFoundException;
import com.arthvritt.platform.shared.error.ValidationException;
import com.arthvritt.platform.tax.TaxEngine.Deduction;
import com.arthvritt.platform.tax.TaxEngine.Position;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BC12 Tax — the investor <b>distribution</b>, the final money-lifecycle step (M16, DL-BE-067). A
 * two-endpoint Treasury maker-checker+MFA gate on a {@code kind='distribution'} {@code cash_payout_instruction}:
 * on a {@code matured_payment_received} listing (buyer repaid the full {@code face_value}), {@code draft}
 * (maker) computes the per-investor TDS breakdown via the shared {@link TaxEngine}, resolves+freezes each
 * investor's FY rate, and writes the drafted instruction carrying an <b>immutable</b> {@code tds_snapshot} in
 * its payload (PI.3 / {@code PI.tds_immutable}). {@code approve} (checker ≠ maker, fresh MFA) pays every
 * investor their net through the BC18 escrow ACL, records the {@code tax_tds_deduction} ledger + each
 * subscription's {@code distribution_outcome}, bumps the FY cumulatives, and <b>closes the deal</b>
 * ({@code matured_payment_received → closed}, {@code terminal_outcome='distributed'} — close <i>is</i> the
 * distribution executing).
 *
 * <p>Boundary (DoR §1): BC4 owns the payout <i>table</i>; M16 owns this command and the tax brain it calls
 * in-tx. TDS base is the return only (M16-Q1); {@code fee = 0} (M16-Q4); rate is 10%/20% from the
 * effective-dated {@code tax_rate_default}, stamped+frozen onto {@code tax_year_profile} (M16-Q2).
 */
@Service
public class DistributionService {

    static final String CONTEXT = "tax";
    private static final Set<String> TREASURY = Set.of(AdminRole.TREASURY_AND_SETTLEMENT.wire());

    private final JdbcTemplate jdbc;
    private final CommandGateway gateway;
    private final RoleResolver roles;
    private final EscrowPort escrow;
    private final AuditLog auditLog;
    private final ObjectMapper json;

    public DistributionService(JdbcTemplate jdbc, CommandGateway gateway, RoleResolver roles, EscrowPort escrow,
                               AuditLog auditLog, ObjectMapper json) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.roles = roles;
        this.escrow = escrow;
        this.auditLog = auditLog;
        this.json = json;
    }

    /**
     * Maker: compute + freeze the distribution. Loads every funded ({@code confirmed}) subscription, resolves
     * each investor's frozen FY rate, runs the {@link TaxEngine}, and records a drafted distribution
     * instruction whose payload carries the immutable {@code tds_snapshot}.
     */
    public CommandResult<UUID> draft(CommandRequest request, UUID listingId) {
        return gateway.execute(request, TREASURY, () -> {
            Listing l = loadListing(listingId);
            if (!"matured_payment_received".equals(l.status())) { // DIS.1 — the spine must have reached maturity
                throw new ValidationException("distribution requires a matured_payment_received listing: "
                        + listingId + " (is " + l.status() + ")");
            }
            if (hasDistribution(listingId)) { // one distribution per listing (app guard)
                throw new ValidationException("a distribution instruction already exists for listing: " + listingId);
            }
            List<Funded> funded = loadFundedSubscriptions(listingId);
            if (funded.isEmpty()) {
                throw new ValidationException("listing has no funded subscriptions to distribute: " + listingId);
            }
            String fyCode = FinancialYear.current();

            // Resolve + freeze each investor's FY rate, then compute the fixed-pot split.
            List<Position> positions = new ArrayList<>(funded.size());
            for (Funded f : funded) {
                int rateBps = resolveAndStampRate(f.investorId(), fyCode);
                positions.add(new Position(f.investorId(), f.amount(), rateBps));
            }
            List<Deduction> deductions = TaxEngine.distribute(l.faceValue(), positions);

            long totalTds = deductions.stream().mapToLong(Deduction::tdsPaise).sum();
            long net = deductions.stream().mapToLong(Deduction::netPaise).sum();
            UUID payoutInstructionId = request.aggregateId(); // = the bank client_instruction_id (PI.1)
            UUID makerAdminId = roles.adminUserId(request.actorId());

            String payload = writeSnapshotPayload(l.faceValue(), net, totalTds, fyCode, funded, deductions);
            try {
                jdbc.update("INSERT INTO cash_payout_instruction (payout_instruction_id, kind, listing_id, status, "
                                + "gross_amount, net_amount, fee_amount, total_tds_amount, payload, maker_id, "
                                + "instruction_sla_date) VALUES (?, 'distribution'::cash_payout_kind, ?, 'drafted', "
                                + "?, ?, 0, ?, ?::jsonb, ?, now()::date + 1)",
                        payoutInstructionId, listingId, l.faceValue(), net, totalTds, payload, makerAdminId);
            } catch (DuplicateKeyException e) {
                throw new ValidationException("a distribution instruction already exists for listing: " + listingId);
            }

            CommandEvent event = new CommandEvent(CONTEXT + ".PayoutInstruction.DistributionDrafted", 1,
                    Map.of("payout_instruction_id", payoutInstructionId.toString(), "listing_id", listingId.toString(),
                            "gross", l.faceValue(), "total_tds", totalTds, "net", net, "investors", funded.size()),
                    Map.of(), Map.of("status", "drafted"), true);
            return new CommandOutcome<>(payoutInstructionId, event);
        });
    }

    /**
     * Checker (≠ maker, fresh MFA): execute the frozen distribution. Reads the immutable snapshot (never
     * recomputes), pays each investor their net via the escrow ACL, writes the TDS ledger + per-subscription
     * outcome, bumps FY cumulatives, and closes the listing as {@code distributed}.
     */
    public CommandResult<Void> approve(CommandRequest request, UUID listingId) {
        return gateway.execute(request, TREASURY, () -> {
            UUID payoutInstructionId = request.aggregateId(); // resolved in the controller
            Payout p = loadPayout(payoutInstructionId);
            if (!"drafted".equals(p.status())) {
                throw new ValidationException("distribution instruction is not drafted: " + payoutInstructionId);
            }
            UUID checkerAdminId = roles.adminUserId(request.actorId());
            if (checkerAdminId.equals(p.makerId())) { // maker ≠ checker (C4/PI.5)
                throw CommandRejectedException.checkerEqualsMaker();
            }
            int approved = jdbc.update("UPDATE cash_payout_instruction SET checker_id = ?, "
                            + "checker_mfa_assertion_id = ?, status = 'approved', "
                            + "aggregate_version = aggregate_version + 1 "
                            + "WHERE payout_instruction_id = ? AND status = 'drafted'",
                    checkerAdminId, request.session().mfaAssertionId().toString(), payoutInstructionId);
            if (approved != 1) {
                throw new ValidationException("distribution instruction is no longer drafted: " + payoutInstructionId);
            }

            // Read the FROZEN snapshot — the tax was computed and immutable at draft (PI.tds_immutable).
            Snapshot snap = readSnapshotPayload(p.payload());

            // Pay every investor their net in one multi-leg escrow instruction (idempotent on the bank key, PI.1).
            List<PayoutLeg> legs = snap.legs().stream()
                    .map(leg -> new PayoutLeg("investor:" + leg.investorId(), leg.net()))
                    .toList();
            escrow.instructPayoutMultiLeg(payoutInstructionId, listingId, legs);

            // Record the TDS ledger + per-subscription outcome + FY cumulatives, all in this tx.
            for (Leg leg : snap.legs()) {
                jdbc.update("INSERT INTO tax_tds_deduction (tds_deduction_id, investor_id, listing_id, fy_code, "
                                + "payout_instruction_id, gross_paise, tds_amount_paise, fee_paise, net_paise) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        UUID.randomUUID(), leg.investorId(), listingId, snap.fyCode(), payoutInstructionId,
                        leg.gross(), leg.tds(), leg.fee(), leg.net());

                String outcome = writeOutcome(leg);
                int advanced = jdbc.update("UPDATE sub_subscription SET distribution_outcome = ?::jsonb, "
                                + "status = 'distribution_received', aggregate_version = aggregate_version + 1 "
                                + "WHERE subscription_id = ? AND status = 'confirmed'",
                        outcome, leg.subscriptionId());
                if (advanced != 1) {
                    throw new ValidationException("subscription is no longer confirmed: " + leg.subscriptionId());
                }

                // cumulative_gross_paise = running TAXABLE INCOME (interest) for the FY; pairs with cumulative
                // TDS to make Form 16A self-sufficient (DL-BE-067). Rowcount-guarded: the profile was stamped at
                // draft, so a 0-row update means the frozen fy_code no longer resolves — reject, never silently
                // drop the cumulative (which would make a later Form 16A under-report income + TDS).
                int bumped = jdbc.update("UPDATE tax_year_profile SET cumulative_gross_paise = cumulative_gross_paise + ?, "
                                + "cumulative_tds_paise = cumulative_tds_paise + ?, "
                                + "aggregate_version = aggregate_version + 1 "
                                + "WHERE investor_id = ? AND fy_code = ?",
                        leg.interest(), leg.tds(), leg.investorId(), snap.fyCode());
                if (bumped != 1) {
                    throw new ValidationException("tax year profile missing for investor " + leg.investorId()
                            + " FY " + snap.fyCode() + " — cannot record cumulative TDS");
                }
            }

            jdbc.update("UPDATE cash_payout_instruction SET status = 'executed', "
                    + "aggregate_version = aggregate_version + 1 WHERE payout_instruction_id = ?", payoutInstructionId);

            // Close the deal: matured_payment_received → closed + terminal_outcome='distributed' (the schema
            // rule couples close to distribution). Status-guarded (the WS-6/WS-7 rowcount lesson).
            int closed = jdbc.update("UPDATE deal_listing SET status = 'closed'::deal_listing_status, "
                            + "terminal_outcome = 'distributed'::deal_terminal_outcome, "
                            + "aggregate_version = aggregate_version + 1 "
                            + "WHERE listing_id = ? AND status = 'matured_payment_received'::deal_listing_status",
                    listingId);
            if (closed != 1) {
                throw new ValidationException("listing is not matured_payment_received; cannot distribute: " + listingId);
            }
            auditLog.append(AuditEnvelopes.seed("listing", "Listing", listingId)
                    .eventType("listing.Listing.Distributed")
                    .actor(actor(request))
                    .payload(Map.of("listing_id", listingId.toString(), "total_tds", snap.totalTds(),
                            "net_distributed", snap.net(), "terminal_outcome", "distributed"))
                    .beforeState(Map.of("status", "matured_payment_received"))
                    .afterState(Map.of("status", "closed", "terminal_outcome", "distributed"))
                    .stateTransition(true).build());

            CommandEvent event = new CommandEvent(CONTEXT + ".PayoutInstruction.DistributionExecuted", 1,
                    Map.of("payout_instruction_id", payoutInstructionId.toString(), "listing_id", listingId.toString(),
                            "total_tds", snap.totalTds(), "net", snap.net()),
                    Map.of("status", "drafted"), Map.of("status", "executed"), true);
            return new CommandOutcome<>(null, event);
        });
    }

    // --- rate resolution + freezing ----------------------------------------------------------------

    /**
     * Returns the investor's frozen TDS rate for the FY, stamping a {@code tax_year_profile} row from the
     * effective-dated {@code tax_rate_default} on first touch (M16-Q2). Once stamped the rate is immutable —
     * a later change to the default never disturbs an already-issued certificate.
     */
    private int resolveAndStampRate(UUID investorId, String fyCode) {
        Integer frozen = jdbc.query("SELECT tds_rate_bps FROM tax_year_profile WHERE investor_id = ? AND fy_code = ?",
                rs -> rs.next() ? rs.getInt(1) : null, investorId, fyCode);
        if (frozen != null) {
            return frozen;
        }
        boolean panVerified = panVerified(investorId);
        int defaultRate = defaultRate(fyCode, panVerified);
        jdbc.update("INSERT INTO tax_year_profile (investor_id, fy_code, tds_rate_bps, pan_verified) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT (investor_id, fy_code) DO NOTHING",
                investorId, fyCode, defaultRate, panVerified);
        // Re-read: under a concurrent stamp, ON CONFLICT DO NOTHING means the winning row's rate is authoritative.
        Integer rate = jdbc.query("SELECT tds_rate_bps FROM tax_year_profile WHERE investor_id = ? AND fy_code = ?",
                rs -> rs.next() ? rs.getInt(1) : null, investorId, fyCode);
        if (rate == null) {
            throw new ValidationException("failed to stamp tax year profile for investor: " + investorId);
        }
        return rate;
    }

    /** PAN-verified signal snapshotted onto the profile: a PAN is on file for the investor. */
    private boolean panVerified(UUID investorId) {
        Boolean present = jdbc.query("SELECT pan IS NOT NULL FROM inv_account WHERE investor_id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null, investorId);
        if (present == null) {
            throw new NotFoundException("investor account not found: " + investorId);
        }
        return present;
    }

    private int defaultRate(String fyCode, boolean panVerified) {
        Integer rate = jdbc.query("SELECT rate_bps FROM tax_rate_default WHERE fy_code = ? AND pan_verified = ?",
                rs -> rs.next() ? rs.getInt(1) : null, fyCode, panVerified);
        if (rate == null) { // fail closed — never guess a rate
            throw new ValidationException("no TDS default rate configured for " + fyCode + " (pan_verified="
                    + panVerified + "); seed tax_rate_default before distributing");
        }
        return rate;
    }

    // --- payload (immutable tds_snapshot) ----------------------------------------------------------

    private String writeSnapshotPayload(long gross, long net, long totalTds, String fyCode, List<Funded> funded,
                                        List<Deduction> deductions) {
        List<Map<String, Object>> legs = new ArrayList<>(deductions.size());
        for (int i = 0; i < deductions.size(); i++) {
            Deduction d = deductions.get(i);
            Map<String, Object> leg = new LinkedHashMap<>();
            leg.put("subscription_id", funded.get(i).subscriptionId().toString());
            leg.put("investor_id", d.investorId().toString());
            leg.put("gross", d.grossPaise());
            leg.put("interest", d.interestPaise());
            leg.put("tds", d.tdsPaise());
            leg.put("fee", d.feePaise());
            leg.put("net", d.netPaise());
            legs.add(leg);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("gross", gross);
        payload.put("net", net);
        payload.put("fee", 0);
        payload.put("total_tds", totalTds);
        payload.put("fy_code", fyCode);
        payload.put("tds_snapshot", legs);
        try {
            return json.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialise tds_snapshot", e);
        }
    }

    private Snapshot readSnapshotPayload(String payloadJson) {
        try {
            Map<?, ?> payload = json.readValue(payloadJson, Map.class);
            String fyCode = (String) payload.get("fy_code");
            long net = ((Number) payload.get("net")).longValue();
            long totalTds = ((Number) payload.get("total_tds")).longValue();
            List<?> raw = (List<?>) payload.get("tds_snapshot");
            List<Leg> legs = new ArrayList<>(raw.size());
            for (Object o : raw) {
                Map<?, ?> m = (Map<?, ?>) o;
                legs.add(new Leg(
                        UUID.fromString((String) m.get("subscription_id")),
                        UUID.fromString((String) m.get("investor_id")),
                        ((Number) m.get("gross")).longValue(),
                        ((Number) m.get("interest")).longValue(),
                        ((Number) m.get("tds")).longValue(),
                        ((Number) m.get("fee")).longValue(),
                        ((Number) m.get("net")).longValue()));
            }
            return new Snapshot(fyCode, net, totalTds, legs);
        } catch (Exception e) {
            throw new IllegalStateException("failed to read frozen tds_snapshot", e);
        }
    }

    private String writeOutcome(Leg leg) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("gross", leg.gross());
        outcome.put("tds", leg.tds());
        outcome.put("fee", leg.fee());
        outcome.put("net", leg.net());
        try {
            return json.writeValueAsString(outcome);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialise distribution_outcome", e);
        }
    }

    // --- loads -------------------------------------------------------------------------------------

    private Listing loadListing(UUID listingId) {
        Listing l = jdbc.query("SELECT l.status::text AS status, i.face_value "
                        + "FROM deal_listing l JOIN deal_invoice i ON i.invoice_id = l.invoice_id "
                        + "WHERE l.listing_id = ?",
                rs -> rs.next() ? new Listing(rs.getString("status"), rs.getLong("face_value")) : null,
                listingId);
        if (l == null) {
            throw new NotFoundException("listing not found: " + listingId);
        }
        return l;
    }

    private List<Funded> loadFundedSubscriptions(UUID listingId) {
        // Deterministic order (by investor_id) so the frozen snapshot and any replay agree.
        return jdbc.query("SELECT subscription_id, investor_id, amount FROM sub_subscription "
                        + "WHERE listing_id = ? AND status = 'confirmed' ORDER BY investor_id",
                (rs, n) -> new Funded(rs.getObject("subscription_id", UUID.class),
                        rs.getObject("investor_id", UUID.class), rs.getLong("amount")),
                listingId);
    }

    private Payout loadPayout(UUID payoutInstructionId) {
        Payout p = jdbc.query("SELECT status::text AS status, maker_id, payload::text AS payload "
                        + "FROM cash_payout_instruction WHERE payout_instruction_id = ? AND kind = 'distribution'",
                rs -> rs.next() ? new Payout(rs.getString("status"), rs.getObject("maker_id", UUID.class),
                        rs.getString("payload")) : null,
                payoutInstructionId);
        if (p == null) {
            throw new NotFoundException("distribution instruction not found: " + payoutInstructionId);
        }
        return p;
    }

    private boolean hasDistribution(UUID listingId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM cash_payout_instruction WHERE listing_id = ? AND kind = 'distribution'",
                Integer.class, listingId);
        return n != null && n > 0;
    }

    private static Actor actor(CommandRequest request) {
        return new Actor("admin_user", request.actorId().toString(),
                request.session().sessionId().toString(), request.session().mfaAssertionId(), null);
    }

    private record Listing(String status, long faceValue) {
    }

    private record Funded(UUID subscriptionId, UUID investorId, long amount) {
    }

    private record Payout(String status, UUID makerId, String payload) {
    }

    private record Snapshot(String fyCode, long net, long totalTds, List<Leg> legs) {
    }

    private record Leg(UUID subscriptionId, UUID investorId, long gross, long interest, long tds, long fee, long net) {
    }
}
