package com.arthvritt.platform.listing;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

/**
 * BC1 listing HTTP surface (WS-4). Thin adapters mapping the request envelope to a {@link CommandRequest}
 * and dispatching through {@link ListingService}/{@code CommandGateway}. The go-live maker (snapshot) and
 * checker (approve) are two distinct endpoints (B4 §6.1); the gateway enforces idempotency / MFA / SoD /
 * audit, the controller only maps HTTP ↔ command.
 */
@RestController
@RequestMapping("/listings")
public class ListingController {

    private static final String CONTEXT = "listing";
    private static final String AGGREGATE_TYPE = "Listing";

    private final ListingService listings;
    private final CommandResponseAssembler responses;
    private final JdbcTemplate jdbc;

    public ListingController(ListingService listings, CommandResponseAssembler responses, JdbcTemplate jdbc) {
        this.listings = listings;
        this.responses = responses;
        this.jdbc = jdbc;
    }

    @PostMapping
    public ResponseEntity<CommandResponse> create(@AuthenticationPrincipal AuthSession session,
                                                  @RequestHeader("X-Command-Id") UUID commandId,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        UUID supplierId = uuid(RequestBodies.requiredString(body, "supplier_id"), "supplier_id");
        UUID buyerId = uuid(RequestBodies.requiredString(body, "buyer_id"), "buyer_id");
        String invoiceNumber = RequestBodies.requiredString(body, "invoice_number");
        long faceValue = RequestBodies.requiredPositivePaise(body, "face_value_paise");
        LocalDate invoiceDate = date(RequestBodies.requiredString(body, "invoice_date"));
        int tenorDays = RequestBodies.requiredPositiveInt(body, "tenor_days");
        String irn = optionalString(body, "irn"); // null = manual-fallback (no IRN); INV.1/INV.7
        // Derive the listing id from the full body so a same-command_id replay is stable.
        UUID listingId = RequestBodies.deriveAggregateId("listing", commandId,
                String.join(":", supplierId.toString(), buyerId.toString(), invoiceNumber,
                        String.valueOf(faceValue), invoiceDate.toString(), String.valueOf(tenorDays),
                        irn == null ? "" : irn));
        CommandRequest request = command(session, commandId, listingId, ".Listing.Create", 0);
        CommandResult<UUID> result = listings.create(request, supplierId, buyerId, invoiceNumber, faceValue,
                invoiceDate, tenorDays, irn);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(responses.from(result));
    }

    @PostMapping("/{id}/start-ops-checks")
    public CommandResponse startOpsChecks(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                          @RequestHeader("X-Command-Id") UUID commandId,
                                          @RequestHeader("X-Aggregate-Version") int version) {
        return responses.from(listings.startOpsChecks(command(session, commandId, id, ".Listing.StartOpsChecks", version)));
    }

    @PostMapping("/{id}/record-ops-check")
    public CommandResponse recordOpsCheck(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                          @RequestHeader("X-Command-Id") UUID commandId,
                                          @RequestHeader("X-Aggregate-Version") int version,
                                          @RequestBody Map<String, Object> body) {
        String checkName = RequestBodies.requiredString(body, "check_name");
        String outcome = optionalString(body, "outcome"); // null for vendor checks (irn_validity)
        CommandRequest request = command(session, commandId, id, ".Listing.RecordOpsCheck", version);
        return responses.from(listings.recordOpsCheck(request, checkName, outcome));
    }

    @PostMapping("/{id}/complete-ops-checks")
    public CommandResponse completeOpsChecks(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                             @RequestHeader("X-Command-Id") UUID commandId,
                                             @RequestHeader("X-Aggregate-Version") int version) {
        return responses.from(listings.completeOpsChecks(
                command(session, commandId, id, ".Listing.CompleteOpsChecks", version)));
    }

    @PostMapping("/{id}/request-buyer-ack")
    public CommandResponse requestBuyerAck(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                           @RequestHeader("X-Command-Id") UUID commandId,
                                           @RequestHeader("X-Aggregate-Version") int version,
                                           @RequestBody Map<String, Object> body) {
        int slaHours = RequestBodies.requiredPositiveInt(body, "sla_hours");
        CommandRequest request = command(session, commandId, id, ".Listing.RequestBuyerAck", version);
        return responses.from(listings.requestBuyerAck(request, slaHours));
    }

    @PostMapping("/{id}/record-buyer-ack")
    public CommandResponse recordBuyerAck(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                          @RequestHeader("X-Command-Id") UUID commandId,
                                          @RequestHeader("X-Aggregate-Version") int version,
                                          @RequestBody Map<String, Object> body) {
        String outcome = RequestBodies.requiredString(body, "outcome");
        String method = optionalString(body, "method");
        String evidenceRef = optionalString(body, "evidence_ref");
        CommandRequest request = command(session, commandId, id, ".Listing.RecordBuyerAck", version);
        return responses.from(listings.recordBuyerAck(request, outcome, method, evidenceRef));
    }

    @PostMapping("/{id}/snapshot-and-ready")
    public CommandResponse snapshotAndReady(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                            @RequestHeader("X-Command-Id") UUID commandId,
                                            @RequestHeader("X-Aggregate-Version") int version,
                                            @RequestBody Map<String, Object> body) {
        int rateBps = RequestBodies.requiredPositiveInt(body, "rate_bps");
        CommandRequest request = command(session, commandId, id, ".Listing.SnapshotAndReady", version);
        return responses.from(listings.snapshotAndReady(request, rateBps));
    }

    @PostMapping("/{id}/approve-go-live")
    public CommandResponse approveGoLive(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                         @RequestHeader("X-Command-Id") UUID commandId,
                                         @RequestHeader("X-Aggregate-Version") int version) {
        return responses.from(listings.approveGoLive(command(session, commandId, id, ".Listing.ApproveGoLive", version)));
    }

    @PostMapping("/{id}/declare-funding-shortfall")
    public CommandResponse declareFundingShortfall(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                                   @RequestHeader("X-Command-Id") UUID commandId,
                                                   @RequestHeader("X-Aggregate-Version") int version) {
        return responses.from(listings.declareFundingShortfall(
                command(session, commandId, id, ".Listing.DeclareFundingShortfall", version)));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id) {
        Map<String, Object> row = jdbc.query(
                "SELECT listing_id, status::text AS status, funding_target, va_id, aggregate_version "
                        + "FROM deal_listing WHERE listing_id = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("listing_id", rs.getObject("listing_id", UUID.class).toString());
                    m.put("status", rs.getString("status"));
                    m.put("funding_target", rs.getObject("funding_target"));
                    UUID vaId = rs.getObject("va_id", UUID.class);
                    m.put("va_id", vaId == null ? null : vaId.toString());
                    m.put("aggregate_version", rs.getInt("aggregate_version"));
                    return m;
                },
                id);
        if (row == null) {
            throw new NotFoundException("listing not found: " + id);
        }
        return row;
    }

    private CommandRequest command(AuthSession session, UUID commandId, UUID listingId, String name, int version) {
        return new CommandRequest(session, commandId, CONTEXT, CONTEXT + name, AGGREGATE_TYPE, listingId,
                version, "admin_user", ActionSensitivity.SENSITIVE);
    }

    /** An optional string field: null when absent/blank; rejects a non-string value with a B4 400. */
    private static String optionalString(Map<String, Object> body, String field) {
        if (body == null) {
            return null;
        }
        Object value = body.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw new ValidationException("field '" + field + "' must be a string");
        }
        return s.isBlank() ? null : s;
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("field '" + field + "' is not a valid id");
        }
    }

    private static LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new ValidationException("invoice_date must be an ISO date (yyyy-MM-dd)");
        }
    }
}
