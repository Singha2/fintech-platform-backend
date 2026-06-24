package com.arthvritt.platform.subscription;

import com.arthvritt.platform.adminiam.AdminRole;
import com.arthvritt.platform.audit.Actor;
import com.arthvritt.platform.audit.AuditEnvelopes;
import com.arthvritt.platform.audit.AuditLog;
import com.arthvritt.platform.command.CommandEvent;
import com.arthvritt.platform.command.CommandGateway;
import com.arthvritt.platform.command.CommandOutcome;
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

    private final JdbcTemplate jdbc;
    private final CommandGateway gateway;
    private final AuditLog auditLog;

    public SubscriptionService(JdbcTemplate jdbc, CommandGateway gateway, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.auditLog = auditLog;
    }

    public CommandResult<UUID> commit(CommandRequest request, UUID listingId, UUID investorId, long amountPaise) {
        if (amountPaise < MIN_TICKET_PAISE) { // S.1
            throw new ValidationException("amount is below the ₹10,000 minimum ticket");
        }
        return gateway.execute(request, OPS, () -> {
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
        return new Actor("admin_user", request.actorId().toString(),
                request.session().sessionId().toString(), request.session().mfaAssertionId(), null);
    }

    private record ListingFunding(String status, long committedTotal, Long fundingTarget) {
    }

    private record Subscription(String status, long expectedInflowAmount) {
    }
}
