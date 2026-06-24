package com.arthvritt.platform.settlement;

import com.arthvritt.platform.auth.ActionSensitivity;
import com.arthvritt.platform.auth.AuthSession;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.infrastructure.web.CommandResponse;
import com.arthvritt.platform.infrastructure.web.CommandResponseAssembler;
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

    private CommandRequest command(AuthSession session, UUID commandId, UUID aggregateId, String name) {
        return new CommandRequest(session, commandId, CONTEXT, CONTEXT + name, AGGREGATE_TYPE, aggregateId,
                0, "admin_user", ActionSensitivity.SENSITIVE);
    }
}
