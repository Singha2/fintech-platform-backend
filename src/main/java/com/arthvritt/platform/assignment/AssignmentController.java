package com.arthvritt.platform.assignment;

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
                                           @RequestHeader("X-Command-Id") UUID commandId) {
        // Target the REAL assignment set (so the audit envelope's aggregate_id matches the aggregate, not a
        // synthetic id). The set already exists from `request`.
        UUID assignmentSetId = jdbc.query(
                "SELECT assignment_set_id FROM legal_assignment_set WHERE listing_id = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, listingId);
        if (assignmentSetId == null) {
            throw new NotFoundException("no assignment set for listing: " + listingId);
        }
        CommandRequest request = command(session, commandId, assignmentSetId, ".AssignmentSet.CompleteSigning");
        return responses.from(assignments.completeSigning(request, listingId));
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
}
