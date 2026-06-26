package com.arthvritt.platform.settlement;

import com.arthvritt.platform.adminiam.AdminRole;
import com.arthvritt.platform.command.CommandEvent;
import com.arthvritt.platform.command.CommandGateway;
import com.arthvritt.platform.command.CommandOutcome;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.shared.error.NotFoundException;
import com.arthvritt.platform.shared.error.ValidationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BC4 Settlement — maturity recording (M13). The single ops command {@code recordMaturity} records the
 * buyer's <b>full</b> maturity repayment and transitions the listing {@code disbursed →
 * matured_payment_received} ({@code Listing.Matured}). The repayment must equal the invoice {@code face_value}
 * (the discount was the investors' return); an under-payment is a maturity shortfall that routes to BC6
 * Collections (M14, deferred). The TDS-bearing investor <b>distribution</b> and the deal <b>close</b> are
 * deferred — close is coupled to distribution by the {@code terminal_outcome='distributed'} schema rule, and
 * distribution needs BC12/Tax (DL-BE-054). Inline + ops-triggered; no maturity/payout webhook.
 */
@Service
public class MaturityService {

    private static final Set<String> OPS = Set.of(AdminRole.OPS_EXECUTIVE.wire());

    private final JdbcTemplate jdbc;
    private final CommandGateway gateway;

    public MaturityService(JdbcTemplate jdbc, CommandGateway gateway) {
        this.jdbc = jdbc;
        this.gateway = gateway;
    }

    public CommandResult<Void> recordMaturity(CommandRequest request, UUID listingId, long amountPaise, String utr) {
        return gateway.execute(request, OPS, () -> {
            Maturing m = load(listingId);
            if (!"disbursed".equals(m.status())) { // MAT.1
                throw new ValidationException("listing is not disbursed: " + listingId + " (is " + m.status() + ")");
            }
            if (amountPaise != m.faceValue()) { // MAT.2 — full repayment only; a shortfall is M14 Collections
                throw new ValidationException("maturity amount must equal the invoice face value "
                        + m.faceValue() + " (an under-payment is a maturity shortfall → Collections, M14)");
            }
            // disbursed → matured_payment_received, status-guarded (the WS-6/WS-7 rowcount lesson).
            int updated = jdbc.update("UPDATE deal_listing SET status = 'matured_payment_received'::deal_listing_status, "
                    + "aggregate_version = aggregate_version + 1 "
                    + "WHERE listing_id = ? AND status = 'disbursed'::deal_listing_status", listingId);
            if (updated != 1) {
                throw new ValidationException("listing is no longer disbursed: " + listingId);
            }
            CommandEvent event = new CommandEvent("listing.Listing.Matured", m.version() + 1,
                    Map.of("listing_id", listingId.toString(), "amount", amountPaise, "utr", utr),
                    Map.of("status", "disbursed"), Map.of("status", "matured_payment_received"), true);
            return new CommandOutcome<>(null, event);
        });
    }

    private Maturing load(UUID listingId) {
        Maturing m = jdbc.query(
                "SELECT l.status::text AS status, i.face_value, l.aggregate_version "
                        + "FROM deal_listing l JOIN deal_invoice i ON i.invoice_id = l.invoice_id "
                        + "WHERE l.listing_id = ?",
                rs -> rs.next()
                        ? new Maturing(rs.getString("status"), rs.getLong("face_value"), rs.getInt("aggregate_version"))
                        : null,
                listingId);
        if (m == null) {
            throw new NotFoundException("listing not found: " + listingId);
        }
        return m;
    }

    private record Maturing(String status, long faceValue, int version) {
    }
}
