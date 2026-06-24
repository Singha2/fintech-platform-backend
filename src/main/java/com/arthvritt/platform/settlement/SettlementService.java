package com.arthvritt.platform.settlement;

import com.arthvritt.platform.audit.Actor;
import com.arthvritt.platform.audit.AuditEnvelopes;
import com.arthvritt.platform.audit.AuditLog;
import com.arthvritt.platform.subscription.SubscriptionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * BC4 Settlement (WS-5) — the inflow reconciliation half. Invoked by the banking webhook handler only when
 * the escrow ACL accepted a fresh (non-duplicate) inflow: it reconciles the provisional
 * {@code gate_inflow_observation}, adds the amount to the listing VA's {@code observed_inflow_total}, and
 * advances the matching subscription to {@code confirmed} (via {@link SubscriptionService}). Because the
 * ACL's {@code vendor_event_id}/{@code utr} dedup gates this call, a re-delivered inflow never reaches here,
 * so {@code observed_inflow_total} is counted exactly once (VI.3).
 *
 * <p>Skeleton scope: single-investor match (the one {@code committed} subscription on the listing). Multi-
 * investor allocation and the EoD master-statement overlay are Milestone 2; the inline call into BC2 stands
 * in for the event bus (DL-BE-035).
 */
@Service
public class SettlementService {

    private final JdbcTemplate jdbc;
    private final SubscriptionService subscriptions;
    private final AuditLog auditLog;

    public SettlementService(JdbcTemplate jdbc, SubscriptionService subscriptions, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.subscriptions = subscriptions;
        this.auditLog = auditLog;
    }

    @Transactional
    public void recordReconciledInflow(UUID vaId, long amountPaise, String utr) {
        // Resolve the VA's listing FIRST — an inflow to an unknown VA is dropped without marking anything
        // reconciled (real: InflowUnmatched + remediation, X3), so we never leave a reconciled-but-unfunded
        // orphan observation.
        UUID listingId = jdbc.query("SELECT listing_id FROM cash_virtual_account WHERE va_id = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, vaId);
        if (listingId == null) {
            return;
        }
        // Reconcile the provisional observation the ACL just recorded (V.4: only reconciled advances funds).
        jdbc.update("UPDATE gate_inflow_observation SET status = 'reconciled' "
                + "WHERE va_id = ? AND utr = ? AND status = 'provisional'", vaId, utr);
        // Add to the VA's observed inflow ledger (V.2).
        jdbc.update("UPDATE cash_virtual_account SET observed_inflow_total = observed_inflow_total + ?, "
                + "aggregate_version = aggregate_version + 1 WHERE va_id = ?", amountPaise, vaId);
        auditLog.append(AuditEnvelopes.seed("settlement", "VirtualAccount", vaId)
                .eventType("settlement.VirtualAccount.InflowReconciled")
                .actor(new Actor("vendor_escrow", "stub-escrow", null, null, null))
                .payload(Map.of("va_id", vaId.toString(), "amount", amountPaise, "utr", utr))
                .build());

        // Match the single committed subscription on the listing (skeleton) and confirm it (S.3).
        UUID subscriptionId = jdbc.query(
                "SELECT subscription_id FROM sub_subscription "
                        + "WHERE listing_id = ? AND status = 'committed' AND expected_inflow_amount = ? LIMIT 1",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, listingId, amountPaise);
        if (subscriptionId != null) {
            subscriptions.confirmFromInflow(subscriptionId, amountPaise, utr);
        }
    }
}
