package com.arthvritt.platform.settlement;

import com.arthvritt.platform.auth.ActionSensitivity;
import com.arthvritt.platform.auth.AuthSession;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.infrastructure.web.CommandResponse;
import com.arthvritt.platform.infrastructure.web.CommandResponseAssembler;
import com.arthvritt.platform.infrastructure.web.ListQuery;
import com.arthvritt.platform.infrastructure.web.RequestBodies;
import com.arthvritt.platform.shared.error.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BC4 disbursement HTTP surface (WS-7). The two-endpoint maker-checker pair (B4 §6): {@code draft} (maker)
 * and {@code approve} (checker). {@code approve} resolves the REAL {@code payout_instruction_id} so its
 * audit envelope chains to the aggregate (the WS-6 lesson). Thin adapters over {@link DisbursementService}.
 */
@RestController
public class DisbursementController {

    private static final String CONTEXT = "settlement";
    private static final String AGGREGATE_TYPE = "PayoutInstruction";

    private final DisbursementService disbursements;
    private final CommandResponseAssembler responses;
    private final JdbcTemplate jdbc;

    public DisbursementController(DisbursementService disbursements, CommandResponseAssembler responses,
                                  JdbcTemplate jdbc) {
        this.disbursements = disbursements;
        this.responses = responses;
        this.jdbc = jdbc;
    }

    @PostMapping("/listings/{listingId}/disbursement/draft")
    public ResponseEntity<CommandResponse> draft(@AuthenticationPrincipal AuthSession session,
                                                 @PathVariable UUID listingId,
                                                 @RequestHeader("X-Command-Id") UUID commandId) {
        // The payout instruction id (= the bank client_instruction_id, PI.1) derives from (command_id, listing).
        UUID payoutInstructionId = RequestBodies.deriveAggregateId("disbursement", commandId, listingId.toString());
        CommandRequest request = command(session, commandId, payoutInstructionId, ".PayoutInstruction.DraftDisbursement");
        CommandResult<UUID> result = disbursements.draft(request, listingId);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(responses.from(result));
    }

    @PostMapping("/listings/{listingId}/disbursement/approve")
    public CommandResponse approve(@AuthenticationPrincipal AuthSession session, @PathVariable UUID listingId,
                                   @RequestHeader("X-Command-Id") UUID commandId) {
        UUID payoutInstructionId = jdbc.query(
                "SELECT payout_instruction_id FROM cash_payout_instruction "
                        + "WHERE listing_id = ? AND kind = 'disbursement'",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, listingId);
        if (payoutInstructionId == null) {
            throw new NotFoundException("no disbursement instruction for listing: " + listingId);
        }
        CommandRequest request = command(session, commandId, payoutInstructionId, ".PayoutInstruction.ApprovePayout");
        return responses.from(disbursements.approve(request, listingId));
    }

    @GetMapping("/listings/{listingId}/disbursement")
    public Map<String, Object> get(@AuthenticationPrincipal AuthSession session, @PathVariable UUID listingId) {
        Map<String, Object> row = jdbc.query(
                "SELECT p.payout_instruction_id, p.status::text AS status, p.gross_amount, p.maker_id, "
                        + "p.checker_id, l.status::text AS listing_status FROM cash_payout_instruction p "
                        + "JOIN deal_listing l ON l.listing_id = p.listing_id "
                        + "WHERE p.listing_id = ? AND p.kind = 'disbursement'",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("payout_instruction_id", rs.getObject("payout_instruction_id", UUID.class).toString());
                    m.put("status", rs.getString("status"));
                    m.put("gross_amount", rs.getLong("gross_amount"));
                    m.put("listing_status", rs.getString("listing_status"));
                    return m;
                },
                listingId);
        if (row == null) {
            throw new NotFoundException("no disbursement instruction for listing: " + listingId);
        }
        return row;
    }

    /**
     * BE-7 (UI_INTEGRATION_BACKEND_SPEC) — the S6 disbursement queue. Additive read over
     * {@code cash_payout_instruction} (kind {@code disbursement}) JOINed to its listing; optional
     * {@code status} filter, {@code LIMIT 500}. The maker's "awaiting draft" queue is
     * {@code GET /listings?status=fully_funded} (BE-6) — this lists the drafted/approved instructions.
     */
    @GetMapping("/disbursements")
    public List<Map<String, Object>> queue(@AuthenticationPrincipal AuthSession session,
                                           @RequestParam(name = "status", required = false) String status) {
        return ListQuery.from(
                        "SELECT p.payout_instruction_id, p.listing_id, p.status::text AS status, p.gross_amount, "
                                + "p.net_amount, p.maker_id, p.checker_id, l.status::text AS listing_status, "
                                + "p.created_at FROM cash_payout_instruction p "
                                + "JOIN deal_listing l ON l.listing_id = p.listing_id")
                .eq("p.kind", "cash_payout_kind", "disbursement")
                .eq("p.status", "cash_payout_status", status)
                .query(jdbc, "ORDER BY p.created_at DESC", (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("payout_instruction_id", rs.getObject("payout_instruction_id", UUID.class).toString());
                    row.put("listing_id", rs.getObject("listing_id", UUID.class).toString());
                    row.put("status", rs.getString("status"));
                    row.put("gross_amount", rs.getLong("gross_amount"));
                    row.put("net_amount", rs.getLong("net_amount"));
                    row.put("maker_id", idOrNull(rs.getObject("maker_id", UUID.class)));
                    row.put("checker_id", idOrNull(rs.getObject("checker_id", UUID.class)));
                    row.put("listing_status", rs.getString("listing_status"));
                    row.put("created_at", rs.getObject("created_at", java.time.OffsetDateTime.class));
                    return row;
                });
    }

    /**
     * BE-7 — the S6 disbursement detail (richer than the frozen {@link #get} by-id, which stays as-is). All
     * real {@code cash_payout_instruction} columns + the listing status. {@code utr} is <b>not</b> a column
     * (it lives only in the payout's audit envelope, {@code DisbursementService}); surface it via the audit
     * read (BE-13) rather than couple this read to the audit payload — promote to a column via migration if a
     * screen needs it inline.
     */
    @GetMapping("/listings/{listingId}/disbursement/detail")
    public Map<String, Object> detail(@AuthenticationPrincipal AuthSession session, @PathVariable UUID listingId) {
        Map<String, Object> row = jdbc.query(
                "SELECT p.payout_instruction_id, p.status::text AS status, p.gross_amount, p.net_amount, "
                        + "p.fee_amount, p.total_tds_amount, p.maker_id, p.checker_id, p.instruction_sla_date, "
                        + "p.created_at, p.updated_at, l.status::text AS listing_status "
                        + "FROM cash_payout_instruction p JOIN deal_listing l ON l.listing_id = p.listing_id "
                        + "WHERE p.listing_id = ? AND p.kind = 'disbursement'",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("payout_instruction_id", rs.getObject("payout_instruction_id", UUID.class).toString());
                    m.put("status", rs.getString("status"));
                    m.put("gross_amount", rs.getLong("gross_amount"));
                    m.put("net_amount", rs.getLong("net_amount"));
                    m.put("fee_amount", rs.getLong("fee_amount"));
                    m.put("total_tds_amount", rs.getObject("total_tds_amount", Long.class));
                    m.put("maker_id", idOrNull(rs.getObject("maker_id", UUID.class)));
                    m.put("checker_id", idOrNull(rs.getObject("checker_id", UUID.class)));
                    m.put("instruction_sla_date", rs.getObject("instruction_sla_date", java.time.LocalDate.class));
                    m.put("created_at", rs.getObject("created_at", java.time.OffsetDateTime.class));
                    m.put("updated_at", rs.getObject("updated_at", java.time.OffsetDateTime.class));
                    m.put("listing_status", rs.getString("listing_status"));
                    return m;
                },
                listingId);
        if (row == null) {
            throw new NotFoundException("no disbursement instruction for listing: " + listingId);
        }
        return row;
    }

    private static String idOrNull(UUID id) {
        return id == null ? null : id.toString();
    }

    private CommandRequest command(AuthSession session, UUID commandId, UUID aggregateId, String name) {
        return new CommandRequest(session, commandId, CONTEXT, CONTEXT + name, AGGREGATE_TYPE, aggregateId,
                0, "admin_user", ActionSensitivity.SENSITIVE);
    }
}
