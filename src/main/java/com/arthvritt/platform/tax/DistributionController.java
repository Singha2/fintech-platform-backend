package com.arthvritt.platform.tax;

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
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BC12 distribution HTTP surface (M16). The two-endpoint maker-checker pair (B4 §6): {@code draft} (maker)
 * and {@code approve} (checker). {@code approve} resolves the REAL {@code payout_instruction_id} so its audit
 * envelope chains to the aggregate (the WS-6 lesson). Thin adapters over {@link DistributionService}.
 */
@RestController
public class DistributionController {

    private static final String CONTEXT = "tax";
    private static final String AGGREGATE_TYPE = "PayoutInstruction";

    private final DistributionService distributions;
    private final CommandResponseAssembler responses;
    private final JdbcTemplate jdbc;

    public DistributionController(DistributionService distributions, CommandResponseAssembler responses,
                                  JdbcTemplate jdbc) {
        this.distributions = distributions;
        this.responses = responses;
        this.jdbc = jdbc;
    }

    @PostMapping("/listings/{listingId}/distribution/draft")
    public ResponseEntity<CommandResponse> draft(@AuthenticationPrincipal AuthSession session,
                                                 @PathVariable UUID listingId,
                                                 @RequestHeader("X-Command-Id") UUID commandId) {
        UUID payoutInstructionId = RequestBodies.deriveAggregateId("distribution", commandId, listingId.toString());
        CommandRequest request = command(session, commandId, payoutInstructionId, ".PayoutInstruction.DraftDistribution");
        CommandResult<UUID> result = distributions.draft(request, listingId);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(responses.from(result));
    }

    @PostMapping("/listings/{listingId}/distribution/approve")
    public CommandResponse approve(@AuthenticationPrincipal AuthSession session, @PathVariable UUID listingId,
                                   @RequestHeader("X-Command-Id") UUID commandId) {
        UUID payoutInstructionId = jdbc.query(
                "SELECT payout_instruction_id FROM cash_payout_instruction "
                        + "WHERE listing_id = ? AND kind = 'distribution'",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, listingId);
        if (payoutInstructionId == null) {
            throw new NotFoundException("no distribution instruction for listing: " + listingId);
        }
        CommandRequest request = command(session, commandId, payoutInstructionId, ".PayoutInstruction.ApproveDistribution");
        return responses.from(distributions.approve(request, listingId));
    }

    @GetMapping("/listings/{listingId}/distribution")
    public Map<String, Object> get(@AuthenticationPrincipal AuthSession session, @PathVariable UUID listingId) {
        Map<String, Object> row = jdbc.query(
                "SELECT p.payout_instruction_id, p.status::text AS status, p.gross_amount, p.net_amount, "
                        + "p.total_tds_amount, l.status::text AS listing_status, l.terminal_outcome::text AS outcome "
                        + "FROM cash_payout_instruction p JOIN deal_listing l ON l.listing_id = p.listing_id "
                        + "WHERE p.listing_id = ? AND p.kind = 'distribution'",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("payout_instruction_id", rs.getObject("payout_instruction_id", UUID.class).toString());
                    m.put("status", rs.getString("status"));
                    m.put("gross_amount", rs.getLong("gross_amount"));
                    m.put("net_amount", rs.getLong("net_amount"));
                    m.put("total_tds_amount", rs.getLong("total_tds_amount"));
                    m.put("listing_status", rs.getString("listing_status"));
                    m.put("terminal_outcome", rs.getString("outcome"));
                    return m;
                },
                listingId);
        if (row == null) {
            throw new NotFoundException("no distribution instruction for listing: " + listingId);
        }
        return row;
    }

    /**
     * BE-8 (UI_INTEGRATION_BACKEND_SPEC) — the S8 per-investor distribution breakdown. Additive read over
     * {@code tax_tds_deduction} (the same table {@link TaxQueryController} reads FY-wide) filtered to one
     * listing: each investor's gross/TDS/fee/net split plus the TDS {@code challan_ref}. No maker-checker
     * columns here — the split rows are written atomically by {@code DistributionService} on approve.
     */
    @GetMapping("/listings/{listingId}/distribution/investors")
    public List<Map<String, Object>> investors(@AuthenticationPrincipal AuthSession session,
                                               @PathVariable UUID listingId) {
        return ListQuery.from(
                        "SELECT investor_id, gross_paise, tds_amount_paise, fee_paise, net_paise, challan_ref "
                                + "FROM tax_tds_deduction")
                .eq("listing_id", listingId)
                .query(jdbc, "ORDER BY investor_id", (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("investor_id", rs.getObject("investor_id", UUID.class).toString());
                    row.put("gross_paise", rs.getLong("gross_paise"));
                    row.put("tds_amount_paise", rs.getLong("tds_amount_paise"));
                    row.put("fee_paise", rs.getLong("fee_paise"));
                    row.put("net_paise", rs.getLong("net_paise"));
                    row.put("challan_ref", rs.getString("challan_ref"));
                    return row;
                });
    }

    private CommandRequest command(AuthSession session, UUID commandId, UUID aggregateId, String name) {
        return new CommandRequest(session, commandId, CONTEXT, CONTEXT + name, AGGREGATE_TYPE, aggregateId,
                0, "admin_user", ActionSensitivity.SENSITIVE);
    }
}
