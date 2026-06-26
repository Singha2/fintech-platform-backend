package com.arthvritt.platform.assignment;

import com.arthvritt.platform.auth.ActionSensitivity;
import com.arthvritt.platform.auth.AuthSession;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.infrastructure.web.CommandResponse;
import com.arthvritt.platform.infrastructure.web.CommandResponseAssembler;
import com.arthvritt.platform.infrastructure.web.RequestBodies;
import com.arthvritt.platform.shared.error.NotFoundException;
import com.arthvritt.platform.shared.error.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BC5 assignment HTTP surface (WS-6). Two ops commands on the listing's assignment set — {@code request}
 * (open the set + initiate the investor's MIA signature) and {@code complete-signing} (drive it to
 * all_signed and open the C27 gate) — plus the set read. Thin adapters over {@link AssignmentService} /
 * {@code CommandGateway}.
 */
@RestController
public class AssignmentController {

    private static final String CONTEXT = "assignment";
    private static final String AGGREGATE_TYPE = "AssignmentSet";

    private final AssignmentService assignments;
    private final CommandResponseAssembler responses;
    private final JdbcTemplate jdbc;

    public AssignmentController(AssignmentService assignments, CommandResponseAssembler responses, JdbcTemplate jdbc) {
        this.assignments = assignments;
        this.responses = responses;
        this.jdbc = jdbc;
    }

    @PostMapping("/listings/{listingId}/assignment-set/request")
    public ResponseEntity<CommandResponse> request(@AuthenticationPrincipal AuthSession session,
                                                   @PathVariable UUID listingId,
                                                   @RequestHeader("X-Command-Id") UUID commandId) {
        // One set per listing (AS.1), so the set id derives from the listing — a replay is stable.
        UUID assignmentSetId = RequestBodies.deriveAggregateId("assignment-set", commandId, listingId.toString());
        CommandRequest request = command(session, commandId, assignmentSetId, ".AssignmentSet.Request");
        CommandResult<UUID> result = assignments.request(request, listingId);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(responses.from(result));
    }

    @PostMapping("/listings/{listingId}/assignment-set/complete-signing")
    public CommandResponse completeSigning(@AuthenticationPrincipal AuthSession session,
                                           @PathVariable UUID listingId,
                                           @RequestHeader("X-Command-Id") UUID commandId,
                                           @RequestBody Map<String, Object> body) {
        UUID investorId = uuid(RequestBodies.requiredString(body, "investor_id"));
        // Target the REAL assignment set (so the audit envelope's aggregate_id matches the aggregate).
        CommandRequest request = command(session, commandId, resolveSetId(listingId), ".AssignmentSet.CompleteSigning");
        return responses.from(assignments.completeSigning(request, listingId, investorId));
    }

    @PostMapping("/listings/{listingId}/assignment-set/declare-incomplete")
    public CommandResponse declareIncomplete(@AuthenticationPrincipal AuthSession session,
                                             @PathVariable UUID listingId,
                                             @RequestHeader("X-Command-Id") UUID commandId) {
        CommandRequest request = command(session, commandId, resolveSetId(listingId), ".AssignmentSet.DeclareIncomplete");
        return responses.from(assignments.declareIncomplete(request, listingId));
    }

    @PostMapping("/listings/{listingId}/assignment-set/record-leg-failed")
    public CommandResponse recordLegFailed(@AuthenticationPrincipal AuthSession session, @PathVariable UUID listingId,
                                           @RequestHeader("X-Command-Id") UUID commandId,
                                           @RequestBody Map<String, Object> body) {
        UUID investorId = uuid(RequestBodies.requiredString(body, "investor_id"));
        String reason = RequestBodies.requiredString(body, "reason");
        CommandRequest request = command(session, commandId, resolveSetId(listingId), ".AssignmentSignature.RecordFailed");
        return responses.from(assignments.recordLegFailed(request, listingId, investorId, reason));
    }

    @PostMapping("/listings/{listingId}/assignment-set/reinitiate-leg")
    public CommandResponse reinitiateLeg(@AuthenticationPrincipal AuthSession session, @PathVariable UUID listingId,
                                         @RequestHeader("X-Command-Id") UUID commandId,
                                         @RequestBody Map<String, Object> body) {
        UUID investorId = uuid(RequestBodies.requiredString(body, "investor_id"));
        CommandRequest request = command(session, commandId, resolveSetId(listingId), ".AssignmentSignature.Reinitiate");
        return responses.from(assignments.reinitiateLeg(request, listingId, investorId));
    }

    @GetMapping("/listings/{listingId}/assignment-set")
    public Map<String, Object> get(@AuthenticationPrincipal AuthSession session, @PathVariable UUID listingId) {
        Map<String, Object> row = jdbc.query(
                "SELECT s.assignment_set_id, s.status::text AS status, s.signed_count, s.total_count, "
                        + "l.all_signed FROM legal_assignment_set s "
                        + "JOIN deal_listing l ON l.listing_id = s.listing_id WHERE s.listing_id = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("assignment_set_id", rs.getObject("assignment_set_id", UUID.class).toString());
                    m.put("status", rs.getString("status"));
                    m.put("signed_count", rs.getInt("signed_count"));
                    m.put("total_count", rs.getInt("total_count"));
                    m.put("all_signed", rs.getBoolean("all_signed"));
                    return m;
                },
                listingId);
        if (row == null) {
            throw new NotFoundException("no assignment set for listing: " + listingId);
        }
        return row;
    }

    private CommandRequest command(AuthSession session, UUID commandId, UUID aggregateId, String name) {
        return new CommandRequest(session, commandId, CONTEXT, CONTEXT + name, AGGREGATE_TYPE, aggregateId,
                0, "admin_user", ActionSensitivity.SENSITIVE);
    }

    private UUID resolveSetId(UUID listingId) {
        UUID setId = jdbc.query("SELECT assignment_set_id FROM legal_assignment_set WHERE listing_id = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, listingId);
        if (setId == null) {
            throw new NotFoundException("no assignment set for listing: " + listingId);
        }
        return setId;
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("investor_id is not a valid id");
        }
    }
}
