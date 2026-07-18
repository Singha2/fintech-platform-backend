package com.arthvritt.platform.buyer;

import com.arthvritt.platform.audit.Actor;
import com.arthvritt.platform.audit.AuditEnvelopes;
import com.arthvritt.platform.audit.AuditLog;
import com.arthvritt.platform.auth.AuthSession;
import com.arthvritt.platform.buyer.port.AckUserQueryPort;
import com.arthvritt.platform.shared.error.ForbiddenException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BC9 buyer-portal HTTP surface (BE-15 Part 2, M11-C, DL-BE-090) — the ack-user's own-scoped reads: its
 * buyer's invoices awaiting acknowledgment and the buyer's payment-instruction metadata. OWN-1: an
 * {@code acknowledgment_user}-kind caller (resolved server-side via {@link AckUserQueryPort}, never a
 * client-supplied id) may read only its <b>own</b> {@code buyer_id} — a cross-buyer read is a clean 403
 * ({@code cross_tenant_read}) and is audited. Only an <b>admin</b> bearer (positively checked) keeps the
 * un-scoped view. Mirrors {@code InvestorController.subscriptions} (BE-14/BE-18).
 */
@RestController
public class BuyerPortalController {

    private final JdbcTemplate jdbc;
    private final AckUserQueryPort ackUsers;
    private final AuditLog auditLog;

    public BuyerPortalController(JdbcTemplate jdbc, AckUserQueryPort ackUsers, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.ackUsers = ackUsers;
        this.auditLog = auditLog;
    }

    @GetMapping("/buyers/{id}/ack-invoices")
    public List<Map<String, Object>> ackInvoices(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id) {
        requireOwnScope(session, id);

        return jdbc.query(
                "SELECT l.listing_id, l.aggregate_version, i.invoice_number, s.legal_name AS supplier_name, "
                        + "i.face_value, i.invoice_date, i.due_date, "
                        + "i.check_outcomes->'buyer_ack'->>'status'       AS ack_status, "
                        + "i.check_outcomes->'buyer_ack'->>'sla_hours'    AS sla_hours, "
                        + "i.check_outcomes->'buyer_ack'->>'requested_at' AS requested_at, "
                        + "i.check_outcomes->'buyer_ack'->>'recorded_at'  AS acknowledged_at "
                        + "FROM deal_listing l JOIN deal_invoice i ON i.invoice_id = l.invoice_id "
                        + "LEFT JOIN sup_account s ON s.supplier_id = l.supplier_id "
                        + "WHERE l.buyer_id = ? AND l.status = 'awaiting_acknowledgment'::deal_listing_status "
                        + "ORDER BY i.due_date",
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("listing_id", rs.getObject("listing_id", UUID.class).toString());
                    row.put("invoice_number", rs.getString("invoice_number"));
                    row.put("supplier_name", rs.getString("supplier_name"));
                    row.put("face_value_paise", rs.getLong("face_value"));
                    row.put("invoice_date", rs.getObject("invoice_date", LocalDate.class));
                    row.put("due_date", rs.getObject("due_date", LocalDate.class));
                    row.put("ack_status", rs.getString("ack_status"));
                    row.put("sla_hours", rs.getString("sla_hours"));
                    row.put("requested_at", rs.getString("requested_at"));
                    row.put("acknowledged_at", rs.getString("acknowledged_at"));
                    row.put("aggregate_version", rs.getInt("aggregate_version"));
                    return row;
                },
                id);
    }

    @GetMapping("/buyers/{id}/payment-instruction")
    public Map<String, Object> paymentInstruction(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id) {
        requireOwnScope(session, id);

        return jdbc.query(
                "SELECT effective_from, confirmed_at FROM buyer_payment_rule "
                        + "WHERE buyer_id = ? AND superseded_by IS NULL",
                rs -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    if (rs.next()) {
                        body.put("present", true);
                        body.put("effective_from", rs.getObject("effective_from", LocalDate.class));
                        body.put("confirmed_at", rs.getObject("confirmed_at", OffsetDateTime.class));
                    } else {
                        body.put("present", false);
                    }
                    return body;
                },
                id);
    }

    /** OWN-1: the ack-user's own {@code buyer_id}, else an admin's un-scoped view — else a clean, audited 403. */
    private void requireOwnScope(AuthSession session, UUID buyerId) {
        UUID callerBuyerId = ackUsers.buyerIdForIdentity(session.identityId()).orElse(null);
        if (callerBuyerId != null) {
            if (!callerBuyerId.equals(buyerId)) {
                auditCrossTenantDenied(session, buyerId, "acknowledgment_user");
                throw ForbiddenException.crossBuyerRead("ack-invoices");
            }
        } else if (!isAdmin(session.identityId())) {
            auditCrossTenantDenied(session, buyerId, "unknown");
            throw ForbiddenException.crossBuyerRead("ack-invoices");
        }
    }

    /**
     * BE-15 Part 2 (M11-C): audits a denied cross-tenant buyer-portal read — a direct, non-command
     * {@link AuditLog#append} (no {@code command_id}), mirroring {@code InvestorController}'s shape.
     * Denials only; a successful own read stays unaudited.
     */
    private void auditCrossTenantDenied(AuthSession session, UUID attemptedBuyerId, String actorKind) {
        auditLog.append(AuditEnvelopes.seed("buyer", "BuyerAccount", attemptedBuyerId)
                .eventType("buyer.CrossTenantReadDenied")
                .actor(new Actor(actorKind, session.identityId().toString(), session.sessionId().toString(), null, null))
                .payload(Map.of("attempted_buyer_id", attemptedBuyerId.toString(),
                        "endpoint", "GET /buyers/{id}/ack-invoices"))
                .build());
    }

    /** A positive "is this identity an admin" check (mirrors {@code SessionController}'s {@code admin_user_id} read). */
    private boolean isAdmin(UUID identityId) {
        UUID adminUserId = jdbc.query("SELECT admin_user_id FROM admin_user WHERE identity_id = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, identityId);
        return adminUserId != null;
    }
}
