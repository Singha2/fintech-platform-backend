package com.arthvritt.platform.subscription;

import com.arthvritt.platform.adminiam.AdminRole;
import com.arthvritt.platform.adminiam.RoleResolver;
import com.arthvritt.platform.audit.Actor;
import com.arthvritt.platform.banking.EscrowPort;
import com.arthvritt.platform.audit.AuditEnvelopes;
import com.arthvritt.platform.audit.AuditLog;
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

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BC2 Subscription (WS-5). The {@code commit} command (ops on-behalf — investor login is M11-full) is a
 * <b>coordinated commit</b>: the {@code sub_subscription} insert and the {@code deal_listing.committed_total}
 * bump happen in one transaction (the gateway tx), and the bump is a single atomic UPDATE carrying the cap
 * predicate ({@code committed_total + amount ≤ funding_target}) — so over-subscription is impossible by
 * construction, not check-then-act (INV-2/INV-3, S.5). When the bump reaches the target exactly the host
 * listing flips to {@code fully_funded} (L.6). {@code confirmFromInflow} is the webhook-driven advance to
 * {@code confirmed} once a reconciled inflow arrives (S.3) — not a command (no {@code command_id}).
 */
@Service
public class SubscriptionService {

    private static final long MIN_TICKET_PAISE = 1_000_000L; // ₹10,000 (S.1, DL-007)
    private static final Set<String> OPS = Set.of(AdminRole.OPS_EXECUTIVE.wire());
    private static final Set<String> TREASURY = Set.of(AdminRole.TREASURY_AND_SETTLEMENT.wire());

    private final JdbcTemplate jdbc;
    private final CommandGateway gateway;
    private final AuditLog auditLog;
    private final RoleResolver roles;
    private final EscrowPort escrow;

    public SubscriptionService(JdbcTemplate jdbc, CommandGateway gateway, AuditLog auditLog, RoleResolver roles,
                               EscrowPort escrow) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.auditLog = auditLog;
        this.roles = roles;
        this.escrow = escrow;
    }

    public CommandResult<UUID> commit(CommandRequest request, UUID listingId, UUID investorId, long amountPaise) {
        if (amountPaise < MIN_TICKET_PAISE) { // S.1
            throw new ValidationException("amount is below the ₹10,000 minimum ticket");
        }
        // BE-18 (M11-B, DoR-4): an investor actor rides the gateway's no-required-roles overload — it may
        // commit only for itself (enforced upstream, controller-side, SELF-1) — while an admin/ops caller
        // stays OPS-gated (SELF-3, no S12 regression).
        Set<String> required = "investor".equals(request.actorType()) ? Set.of() : OPS;
        return gateway.execute(request, required, () -> {
            requireActiveInvestor(investorId); // S.4
            ListingFunding lf = loadFunding(listingId);
            if (!"live".equals(lf.status())) { // S.5
                throw new ValidationException("listing is not live: " + listingId);
            }

            // Atomic coordinated bump + FullyFunded flip in ONE statement (no stale before-image): the cap
            // predicate makes over-subscription impossible (rowcount 0 → reject), and the CASE flips to
            // fully_funded exactly when the NEW committed_total equals funding_target (L.6). All SET RHS see
            // the pre-update row, so `committed_total + ?` is the new total. RETURNING gives the result so
            // the flip decision is never read from a racy before-image.
            String newStatus = jdbc.query("UPDATE deal_listing "
                            + "SET committed_total = committed_total + ?, "
                            + "    status = CASE WHEN committed_total + ? = funding_target "
                            + "                  THEN 'fully_funded'::deal_listing_status ELSE status END, "
                            + "    aggregate_version = aggregate_version + 1 "
                            + "WHERE listing_id = ? AND status = 'live' AND committed_total + ? <= funding_target "
                            + "RETURNING status::text",
                    rs -> rs.next() ? rs.getString(1) : null,
                    amountPaise, amountPaise, listingId, amountPaise);
            if (newStatus == null) {
                throw new ValidationException("commit would over-subscribe the listing (committed + amount > funding_target)");
            }

            UUID subscriptionId = request.aggregateId();
            try {
                jdbc.update("INSERT INTO sub_subscription (subscription_id, listing_id, investor_id, amount, "
                                + "status, expected_inflow_amount) VALUES (?, ?, ?, ?, 'committed', ?)",
                        subscriptionId, listingId, investorId, amountPaise, amountPaise);
            } catch (DuplicateKeyException e) { // S.6 one subscription per (listing, investor)
                throw new ValidationException("this investor already has a subscription on the listing");
            }

            // FullyFunded (L.6) — a 2nd envelope from this command (like approveDisableAdmin).
            if ("fully_funded".equals(newStatus)) {
                auditLog.append(AuditEnvelopes.seed("listing", "Listing", listingId)
                        .eventType("listing.Listing.FullyFunded")
                        .actor(actor(request))
                        .payload(Map.of("listing_id", listingId.toString(),
                                "committed_total", lf.fundingTarget()))
                        .beforeState(Map.of("status", "live")).afterState(Map.of("status", "fully_funded"))
                        .stateTransition(true).build());
            }

            CommandEvent event = new CommandEvent("subscription.Subscription.Committed", 1,
                    Map.of("subscription_id", subscriptionId.toString(), "listing_id", listingId.toString(),
                            "amount", amountPaise),
                    Map.of(), Map.of("status", "committed"), true);
            return new CommandOutcome<>(subscriptionId, event);
        });
    }

    /**
     * Pre-confirmation cancellation (S.2 — only from {@code committed}, before funds): flips the subscription
     * to {@code cancelled_by_investor} and <b>releases</b> the host listing's {@code committed_total} in one
     * atomic statement (the inverse of the WS-5 coordinated commit) — a {@code fully_funded} listing returns
     * to {@code live}. Ops-on-behalf (investor login is deferred).
     */
    public CommandResult<Void> cancel(CommandRequest request) {
        return gateway.execute(request, OPS, () -> {
            UUID subscriptionId = request.aggregateId();
            SubRow sub = loadSubRow(subscriptionId);
            if (sub == null) {
                throw new NotFoundException("subscription not found: " + subscriptionId);
            }
            if (!"committed".equals(sub.status())) { // S.2: cancellation only before funds are received
                throw new ValidationException("only a committed subscription can be cancelled: " + subscriptionId
                        + " (is " + sub.status() + ")");
            }
            // Flip the subscription first (version-guarded) so a concurrent double-cancel loses cleanly
            // before any listing release.
            int flipped = jdbc.update("UPDATE sub_subscription SET status = 'cancelled_by_investor', "
                            + "aggregate_version = aggregate_version + 1 "
                            + "WHERE subscription_id = ? AND status = 'committed' AND aggregate_version = ?",
                    subscriptionId, request.expectedVersion());
            if (flipped != 1) {
                throw CommandRejectedException.versionConflict(request.expectedVersion(), loadSubRow(subscriptionId).version());
            }
            // Atomic release: decrement committed_total and, if it was full, reopen to 'live' — driven off
            // the true row, never a stale before-image (mirrors the WS-5 commit's bump+CASE).
            String beforeStatus = loadFunding(sub.listingId()).status();
            String afterStatus = jdbc.query("UPDATE deal_listing "
                            + "SET committed_total = committed_total - ?, "
                            + "    status = CASE WHEN status = 'fully_funded' THEN 'live'::deal_listing_status "
                            + "                  ELSE status END, "
                            + "    aggregate_version = aggregate_version + 1 "
                            + "WHERE listing_id = ? AND status IN ('live', 'fully_funded') "
                            + "RETURNING status::text",
                    rs -> rs.next() ? rs.getString(1) : null, sub.amount(), sub.listingId());
            if (afterStatus == null) {
                throw new ValidationException("listing is not in a releasable state: " + sub.listingId());
            }
            // A reopened listing (fully_funded → live) is a significant milestone — its own envelope (the
            // symmetric counterpart of the WS-5 FullyFunded envelope).
            if ("fully_funded".equals(beforeStatus) && "live".equals(afterStatus)) {
                auditLog.append(AuditEnvelopes.seed("listing", "Listing", sub.listingId())
                        .eventType("listing.Listing.FundingReleased")
                        .actor(actor(request))
                        .payload(Map.of("listing_id", sub.listingId().toString(), "released_amount", sub.amount()))
                        .beforeState(Map.of("status", "fully_funded")).afterState(Map.of("status", "live"))
                        .stateTransition(true).build());
            }
            CommandEvent event = new CommandEvent("subscription.Subscription.CancelledByInvestor",
                    request.expectedVersion() + 1,
                    Map.of("subscription_id", subscriptionId.toString(), "listing_id", sub.listingId().toString(),
                            "amount", sub.amount()),
                    Map.of("status", "committed"), Map.of("status", "cancelled_by_investor"), true);
            return new CommandOutcome<>(null, event);
        });
    }

    /**
     * Advances a subscription {@code committed → confirmed} on a reconciled inflow (S.3: the confirmed
     * amount must equal the committed amount). Webhook-driven and idempotent: a second reconciled delivery
     * for an already-confirmed subscription is a no-op. Runs in the caller's (settlement) transaction.
     */
    public void confirmFromInflow(UUID subscriptionId, long amountPaise, String utr) {
        Subscription sub = jdbc.query(
                "SELECT status::text AS status, expected_inflow_amount FROM sub_subscription WHERE subscription_id = ?",
                rs -> rs.next() ? new Subscription(rs.getString("status"), rs.getLong("expected_inflow_amount")) : null,
                subscriptionId);
        if (sub == null) {
            throw new NotFoundException("subscription not found: " + subscriptionId);
        }
        if (!"committed".equals(sub.status())) {
            return; // already confirmed (or beyond) — idempotent no-op
        }
        if (sub.expectedInflowAmount() != amountPaise) { // S.3 paise equality
            throw new ValidationException("inflow amount does not match the subscription's expected amount");
        }
        jdbc.update("UPDATE sub_subscription SET status = 'confirmed', actual_inflow_txn_ref = ?, "
                + "aggregate_version = aggregate_version + 1 WHERE subscription_id = ? AND status = 'committed'",
                utr, subscriptionId);
        auditLog.append(AuditEnvelopes.seed("subscription", "Subscription", subscriptionId)
                .eventType("subscription.Subscription.Confirmed")
                .actor(new Actor("vendor_escrow", "stub-escrow", null, null, null))
                .payload(Map.of("subscription_id", subscriptionId.toString(), "amount", amountPaise))
                .beforeState(Map.of("status", "committed")).afterState(Map.of("status", "confirmed"))
                .stateTransition(true).build());
    }

    /**
     * Refunds a subscription on a shortfall-failed listing (M11-B): a {@code confirmed} (funded) position is
     * refunded inline through the BC18 escrow ACL ({@code instructRefund}, recorded as a {@code kind=refund}
     * {@code cash_payout_instruction}); a {@code committed} (unfunded) position flips with no money movement.
     * Both land in {@code refunded} (the money-returned terminal; the explicit {@code Close} is folded).
     * Treasury command (money out). Idempotent on {@code command_id}; a second attempt on a refunded position
     * is rejected by the status guard.
     */
    public CommandResult<Void> recordRefund(CommandRequest request) {
        return gateway.execute(request, TREASURY, () -> {
            UUID subscriptionId = request.aggregateId();
            SubRow sub = loadSubRow(subscriptionId);
            if (sub == null) {
                throw new NotFoundException("subscription not found: " + subscriptionId);
            }
            String listingStatus = loadFunding(sub.listingId()).status();
            if (!"funding_failed_refunded".equals(listingStatus)) { // PI.4: only after a declared shortfall
                throw new ValidationException("listing has not declared a funding shortfall: " + sub.listingId());
            }
            if (!"committed".equals(sub.status()) && !"confirmed".equals(sub.status())) {
                throw new ValidationException("subscription is not refundable: " + subscriptionId
                        + " (is " + sub.status() + ")");
            }
            // A funded (confirmed) position returns money through the escrow ACL + a kind=refund payout row.
            if ("confirmed".equals(sub.status())) {
                // The payout id is DERIVED from the subscription so it is the SAME across any retry/concurrent
                // refund: that makes escrow.instructRefund idempotent on its key (never a double vendor refund,
                // which a real non-transactional adapter would NOT roll back), and the payout PK enforces
                // one refund row per subscription (PI.4 backstop, no migration needed).
                UUID payoutInstructionId = UUID.nameUUIDFromBytes(
                        ("refund:" + subscriptionId).getBytes(StandardCharsets.UTF_8));
                try {
                    jdbc.update("INSERT INTO cash_payout_instruction (payout_instruction_id, kind, subscription_id, "
                                    + "status, gross_amount, net_amount, fee_amount, maker_id, instruction_sla_date) "
                                    + "VALUES (?, 'refund'::cash_payout_kind, ?, 'drafted', ?, ?, 0, ?, now()::date + 1)",
                            payoutInstructionId, subscriptionId, sub.amount(), sub.amount(),
                            roles.adminUserId(request.actorId()));
                } catch (DuplicateKeyException e) { // a refund for this subscription is already in flight/done
                    throw new ValidationException("a refund already exists for this subscription: " + subscriptionId);
                }
                escrow.instructRefund(payoutInstructionId, subscriptionId, sub.amount()); // idempotent on the id
                jdbc.update("UPDATE cash_payout_instruction SET status = 'executed', "
                        + "aggregate_version = aggregate_version + 1 WHERE payout_instruction_id = ?",
                        payoutInstructionId);
            }
            int refunded = jdbc.update("UPDATE sub_subscription SET status = 'refunded', "
                            + "aggregate_version = aggregate_version + 1 "
                            + "WHERE subscription_id = ? AND status = ?::sub_subscription_status "
                            + "AND aggregate_version = ?",
                    subscriptionId, sub.status(), request.expectedVersion());
            if (refunded != 1) {
                throw CommandRejectedException.versionConflict(request.expectedVersion(), loadSubRow(subscriptionId).version());
            }
            CommandEvent event = new CommandEvent("subscription.Subscription.Refunded", request.expectedVersion() + 1,
                    Map.of("subscription_id", subscriptionId.toString(), "amount", sub.amount()),
                    Map.of("status", sub.status()), Map.of("status", "refunded"), true);
            return new CommandOutcome<>(null, event);
        });
    }

    // --- helpers -----------------------------------------------------------------------------------

    private void requireActiveInvestor(UUID investorId) {
        String status = jdbc.query("SELECT status::text FROM inv_account WHERE investor_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, investorId);
        if (status == null) {
            throw new NotFoundException("investor not found: " + investorId);
        }
        if (!"active".equals(status)) {
            throw new ValidationException("investor is not active: " + investorId);
        }
    }

    private SubRow loadSubRow(UUID subscriptionId) {
        return jdbc.query("SELECT status::text AS status, listing_id, amount, aggregate_version "
                        + "FROM sub_subscription WHERE subscription_id = ?",
                rs -> rs.next()
                        ? new SubRow(rs.getString("status"), rs.getObject("listing_id", UUID.class),
                                rs.getLong("amount"), rs.getInt("aggregate_version"))
                        : null,
                subscriptionId);
    }

    private ListingFunding loadFunding(UUID listingId) {
        ListingFunding lf = jdbc.query(
                "SELECT status::text AS status, committed_total, funding_target FROM deal_listing WHERE listing_id = ?",
                rs -> rs.next()
                        ? new ListingFunding(rs.getString("status"), rs.getLong("committed_total"),
                                (Long) rs.getObject("funding_target"))
                        : null,
                listingId);
        if (lf == null) {
            throw new NotFoundException("listing not found: " + listingId);
        }
        if (lf.fundingTarget() == null) {
            throw new ValidationException("listing has no funding_target (not yet priced): " + listingId);
        }
        return lf;
    }

    private static Actor actor(CommandRequest request) {
        return new Actor(request.actorType(), request.actorId().toString(),
                request.session().sessionId().toString(), request.session().mfaAssertionId(), null);
    }

    private record ListingFunding(String status, long committedTotal, Long fundingTarget) {
    }

    private record Subscription(String status, long expectedInflowAmount) {
    }

    private record SubRow(String status, UUID listingId, long amount, int version) {
    }
}
