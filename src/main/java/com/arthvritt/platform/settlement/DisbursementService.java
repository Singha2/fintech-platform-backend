package com.arthvritt.platform.settlement;

import com.arthvritt.platform.adminiam.AdminRole;
import com.arthvritt.platform.adminiam.RoleResolver;
import com.arthvritt.platform.audit.Actor;
import com.arthvritt.platform.audit.AuditEnvelopes;
import com.arthvritt.platform.audit.AuditLog;
import com.arthvritt.platform.banking.EscrowAclService;
import com.arthvritt.platform.banking.EscrowPort.PayoutResult;
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
 * BC4 Disbursement (WS-7) — the second two-endpoint maker-checker+MFA gate (the twin of WS-4 go-live, but
 * on {@code cash_payout_instruction}). {@code draft} (Treasury <b>maker</b>) is allowed only behind the C27
 * gate ({@code fully_funded ∧ all_signed}, PI.2) and records the payout instruction (its PK is also the bank
 * {@code client_instruction_id}, PI.1). {@code approve} (Treasury <b>checker</b> ≠ maker, fresh MFA, PI.5)
 * instructs the escrow payout via the M5b {@link EscrowAclService} (inline) and flips the listing
 * {@code fully_funded → disbursed} — the end of the {@code listed → disbursed} spine.
 */
@Service
public class DisbursementService {

    private static final String CONTEXT = "settlement";
    private static final Set<String> TREASURY = Set.of(AdminRole.TREASURY_AND_SETTLEMENT.wire());

    private final JdbcTemplate jdbc;
    private final CommandGateway gateway;
    private final RoleResolver roles;
    private final EscrowAclService escrow;
    private final AuditLog auditLog;

    public DisbursementService(JdbcTemplate jdbc, CommandGateway gateway, RoleResolver roles,
                               EscrowAclService escrow, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.roles = roles;
        this.escrow = escrow;
        this.auditLog = auditLog;
    }

    public CommandResult<UUID> draft(CommandRequest request, UUID listingId) {
        return gateway.execute(request, TREASURY, () -> {
            Listing l = loadListing(listingId);
            if (!"fully_funded".equals(l.status()) || !l.allSigned()) { // PI.2 / C27
                throw new ValidationException("disbursement requires a fully_funded, all_signed listing: " + listingId);
            }
            if (hasDisbursement(listingId)) { // fast-path app guard (the V6 partial UNIQUE is the backstop)
                throw new ValidationException("a disbursement instruction already exists for listing: " + listingId);
            }
            UUID payoutInstructionId = request.aggregateId(); // = the bank client_instruction_id (PI.1)
            UUID makerAdminId = roles.adminUserId(request.actorId());
            // Disbursement pays the supplier the discounted value: gross = net = funding_target, fee = 0.
            // A concurrent double-draft that slips past the app guard hits uidx_cash_payout_disbursement_per_listing
            // (one disbursement per listing, PI.2) → clean 400 rather than two drafted instructions.
            try {
                jdbc.update("INSERT INTO cash_payout_instruction (payout_instruction_id, kind, listing_id, status, "
                                + "gross_amount, net_amount, fee_amount, maker_id, instruction_sla_date) "
                                + "VALUES (?, 'disbursement'::cash_payout_kind, ?, 'drafted', ?, ?, 0, ?, "
                                + "now()::date + 1)",
                        payoutInstructionId, listingId, l.fundingTarget(), l.fundingTarget(), makerAdminId);
            } catch (DuplicateKeyException e) {
                throw new ValidationException("a disbursement instruction already exists for listing: " + listingId);
            }
            CommandEvent event = new CommandEvent(CONTEXT + ".PayoutInstruction.DisbursementDrafted", 1,
                    Map.of("payout_instruction_id", payoutInstructionId.toString(), "listing_id", listingId.toString(),
                            "amount", l.fundingTarget()),
                    Map.of(), Map.of("status", "drafted"), true);
            return new CommandOutcome<>(payoutInstructionId, event);
        });
    }

    public CommandResult<Void> approve(CommandRequest request, UUID listingId) {
        return gateway.execute(request, TREASURY, () -> {
            UUID payoutInstructionId = request.aggregateId(); // the real instruction id (resolved in the controller)
            Payout p = loadPayout(payoutInstructionId);
            if (!"drafted".equals(p.status())) {
                throw new ValidationException("payout instruction is not drafted: " + payoutInstructionId);
            }
            UUID checkerAdminId = roles.adminUserId(request.actorId());
            // Maker ≠ checker (C4/PI.5) — clean 409 in-app, DB CHECK as the backstop.
            if (checkerAdminId.equals(p.makerId())) {
                throw CommandRejectedException.checkerEqualsMaker();
            }
            int approved = jdbc.update("UPDATE cash_payout_instruction SET checker_id = ?, "
                            + "checker_mfa_assertion_id = ?, status = 'approved', "
                            + "aggregate_version = aggregate_version + 1 "
                            + "WHERE payout_instruction_id = ? AND status = 'drafted'",
                    checkerAdminId, request.session().mfaAssertionId().toString(), payoutInstructionId);
            if (approved != 1) {
                throw new ValidationException("payout instruction is no longer drafted: " + payoutInstructionId);
            }

            // Instruct the escrow payout (inline; idempotent on payout_instruction_id = the bank key, PI.1).
            Listing l = loadListing(listingId);
            PayoutResult result = escrow.instructPayoutSingle(payoutInstructionId, listingId, l.fundingTarget(),
                    "supplier:" + l.supplierId());
            jdbc.update("UPDATE cash_payout_instruction SET status = 'executed', "
                    + "aggregate_version = aggregate_version + 1 WHERE payout_instruction_id = ?", payoutInstructionId);

            // Flip the listing fully_funded → disbursed (assert the row, mirroring the WS-6 gate lesson).
            int disbursed = jdbc.update("UPDATE deal_listing SET status = 'disbursed', "
                    + "aggregate_version = aggregate_version + 1 WHERE listing_id = ? AND status = 'fully_funded'",
                    listingId);
            if (disbursed != 1) {
                throw new ValidationException("listing is not fully_funded; cannot disburse: " + listingId);
            }
            auditLog.append(AuditEnvelopes.seed("listing", "Listing", listingId)
                    .eventType("listing.Listing.Disbursed")
                    .actor(actor(request))
                    .payload(Map.of("listing_id", listingId.toString(), "utr", result.utr()))
                    .beforeState(Map.of("status", "fully_funded")).afterState(Map.of("status", "disbursed"))
                    .stateTransition(true).build());

            CommandEvent event = new CommandEvent(CONTEXT + ".PayoutInstruction.DisbursementInstructed", 1,
                    Map.of("payout_instruction_id", payoutInstructionId.toString(), "listing_id", listingId.toString()),
                    Map.of("status", "drafted"), Map.of("status", "executed"), true);
            return new CommandOutcome<>(null, event);
        });
    }

    // --- helpers -----------------------------------------------------------------------------------

    private Listing loadListing(UUID listingId) {
        Listing l = jdbc.query("SELECT status::text AS status, all_signed, funding_target, supplier_id "
                        + "FROM deal_listing WHERE listing_id = ?",
                rs -> rs.next()
                        ? new Listing(rs.getString("status"), rs.getBoolean("all_signed"),
                                (Long) rs.getObject("funding_target"), rs.getObject("supplier_id", UUID.class))
                        : null,
                listingId);
        if (l == null) {
            throw new NotFoundException("listing not found: " + listingId);
        }
        if (l.fundingTarget() == null) {
            throw new ValidationException("listing has no funding_target: " + listingId);
        }
        return l;
    }

    private Payout loadPayout(UUID payoutInstructionId) {
        Payout p = jdbc.query("SELECT status::text AS status, maker_id FROM cash_payout_instruction "
                        + "WHERE payout_instruction_id = ?",
                rs -> rs.next() ? new Payout(rs.getString("status"), rs.getObject("maker_id", UUID.class)) : null,
                payoutInstructionId);
        if (p == null) {
            throw new NotFoundException("payout instruction not found: " + payoutInstructionId);
        }
        return p;
    }

    private boolean hasDisbursement(UUID listingId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM cash_payout_instruction WHERE listing_id = ? AND kind = 'disbursement'",
                Integer.class, listingId);
        return n != null && n > 0;
    }

    private static Actor actor(CommandRequest request) {
        return new Actor("admin_user", request.actorId().toString(),
                request.session().sessionId().toString(), request.session().mfaAssertionId(), null);
    }

    private record Listing(String status, boolean allSigned, Long fundingTarget, UUID supplierId) {
    }

    private record Payout(String status, UUID makerId) {
    }
}
