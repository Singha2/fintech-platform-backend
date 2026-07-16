package com.arthvritt.platform.listing;

import com.arthvritt.platform.auth.ActionSensitivity;
import com.arthvritt.platform.auth.AuthSession;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.infrastructure.web.CommandResponse;
import com.arthvritt.platform.infrastructure.web.CommandResponseAssembler;
import com.arthvritt.platform.infrastructure.web.ListQuery;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
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

    /**
     * BE-6 (UI_INTEGRATION_BACKEND_SPEC) — the S5 listing list. Additive JOIN read over {@code deal_listing}
     * + {@code deal_invoice} (invoice display fields live on the invoice); optional {@code status} /
     * {@code supplier_id} / {@code buyer_id} filters, {@code LIMIT 500}. {@code rate_bps} is read from the
     * frozen {@code pricing_snapshot} JSONB (null until snapshot). Authenticated-only.
     */
    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal AuthSession session,
                                          @RequestParam(name = "status", required = false) String status,
                                          @RequestParam(name = "supplier_id", required = false) UUID supplierId,
                                          @RequestParam(name = "buyer_id", required = false) UUID buyerId) {
        return ListQuery.from(
                        "SELECT l.listing_id, i.invoice_number, l.supplier_id, l.buyer_id, "
                                + "i.face_value AS face_value_paise, i.tenor_days, l.status::text AS status, "
                                + "l.funding_target, (l.pricing_snapshot ->> 'rate_bps')::int AS rate_bps "
                                + "FROM deal_listing l JOIN deal_invoice i ON i.invoice_id = l.invoice_id")
                .eq("l.status", "deal_listing_status", status)
                .eq("l.supplier_id", supplierId)
                .eq("l.buyer_id", buyerId)
                .query(jdbc, "ORDER BY l.created_at DESC", (rs, n) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("listing_id", rs.getObject("listing_id", UUID.class).toString());
                    row.put("invoice_number", rs.getString("invoice_number"));
                    row.put("supplier_id", rs.getObject("supplier_id", UUID.class).toString());
                    row.put("buyer_id", rs.getObject("buyer_id", UUID.class).toString());
                    row.put("face_value_paise", rs.getLong("face_value_paise"));
                    row.put("tenor_days", rs.getInt("tenor_days"));
                    row.put("status", rs.getString("status"));
                    row.put("funding_target", rs.getObject("funding_target", Long.class));
                    row.put("rate_bps", rs.getObject("rate_bps", Integer.class));
                    return row;
                });
    }

    /**
     * BE-6 — the S5 ops-check panel. Expands the invoice's {@code check_outcomes} JSONB into rows via
     * {@code jsonb_each} (the same map {@code record-ops-check} writes). 404 for an unknown listing; an empty
     * list when no checks have been recorded yet.
     */
    @GetMapping("/{id}/ops-checks")
    public List<Map<String, Object>> opsChecks(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id) {
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM deal_listing WHERE listing_id = ?",
                Integer.class, id);
        if (exists == null || exists == 0) {
            throw new NotFoundException("listing not found: " + id);
        }
        return jdbc.query(
                "SELECT e.key AS check_name, e.value ->> 'outcome' AS outcome, "
                        + "e.value ->> 'verification_id' AS verification_id, e.value ->> 'checked_at' AS checked_at "
                        + "FROM deal_listing l JOIN deal_invoice i ON i.invoice_id = l.invoice_id, "
                        + "LATERAL jsonb_each(i.check_outcomes) e WHERE l.listing_id = ? ORDER BY e.key",
                (rs, n) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("check_name", rs.getString("check_name"));
                    row.put("outcome", rs.getString("outcome"));
                    row.put("verification_id", rs.getString("verification_id"));
                    row.put("checked_at", rs.getString("checked_at"));
                    return row;
                },
                id);
    }

    /**
     * BE-10 (UI_INTEGRATION_BACKEND_SPEC) — the S12 rich listing detail (admin variant). A <b>new</b> read
     * <i>alongside</i> the frozen {@code GET /listings/{id}} (which stays a thin status/version read). Composes
     * a read-model over {@code deal_listing} + its frozen {@code pricing_snapshot} + {@code cash_virtual_account}
     * (1:1, {@code UNIQUE(listing_id)}) + {@code deal_invoice} + the buyer/supplier counterparties. The cross-BC
     * reach is a raw-SQL read-model (no Java-level coupling — ArchUnit ARCH.1 stays green; same shape as
     * {@code DisbursementController}'s listing JOIN); the ownership + KYC gate for the <i>investor</i> S12
     * variant is BE-14/M10-full, not here. {@code pricing_snapshot}/{@code virtual_account} are {@code null}
     * until the listing is priced / gets its VA. 404 for an unknown listing.
     */
    @GetMapping("/{id}/detail")
    public Map<String, Object> detail(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id) {
        Map<String, Object> detail = jdbc.query(
                "SELECT l.listing_id, l.status::text AS listing_status, l.funding_target, l.committed_total, "
                        + "l.va_id, (l.pricing_snapshot ->> 'rate_bps')::int AS rate_bps, "
                        + "(l.pricing_snapshot ->> 'fee_bps')::int AS fee_bps, "
                        + "(l.pricing_snapshot ->> 'pricing_band_id') AS pricing_band_id, "
                        + "v.account_no AS va_account_no, v.ifsc AS va_ifsc, v.status::text AS va_status, "
                        + "i.invoice_number, i.face_value AS face_value_paise, i.tenor_days, i.invoice_date, "
                        + "i.due_date, i.irn::text AS irn, "
                        + "l.buyer_id, b.legal_name AS buyer_legal_name, b.sector AS buyer_sector, "
                        + "b.status::text AS buyer_status, b.mca_cin AS buyer_mca_cin, b.gstin::text AS buyer_gstin, "
                        + "b.credit_limit_paise AS buyer_credit_limit_paise, "
                        + "l.supplier_id, s.legal_name AS supplier_legal_name, "
                        + "s.constitution_type::text AS supplier_constitution_type, s.pan::text AS supplier_pan, "
                        + "s.gstin::text AS supplier_gstin, s.status::text AS supplier_status "
                        + "FROM deal_listing l "
                        + "JOIN deal_invoice i ON i.invoice_id = l.invoice_id "
                        // buyer/supplier are cross-BC logical refs (no FK), so LEFT JOIN — a missing counterparty
                        // projection must not 404 an existing listing; the ids come from the listing regardless.
                        + "LEFT JOIN buyer_account b ON b.buyer_id = l.buyer_id "
                        + "LEFT JOIN sup_account s ON s.supplier_id = l.supplier_id "
                        + "LEFT JOIN cash_virtual_account v ON v.listing_id = l.listing_id "
                        + "WHERE l.listing_id = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("listing_id", rs.getObject("listing_id", UUID.class).toString());
                    m.put("status", rs.getString("listing_status"));
                    m.put("funding_target", rs.getObject("funding_target", Long.class));
                    m.put("committed_total", rs.getLong("committed_total"));
                    UUID vaId = rs.getObject("va_id", UUID.class);
                    m.put("va_id", vaId == null ? null : vaId.toString());

                    Integer rateBps = rs.getObject("rate_bps", Integer.class);
                    if (rateBps == null) {
                        m.put("pricing_snapshot", null);   // not priced yet
                    } else {
                        Map<String, Object> ps = new java.util.LinkedHashMap<>();
                        ps.put("rate_bps", rateBps);
                        ps.put("fee_bps", rs.getObject("fee_bps", Integer.class));
                        ps.put("pricing_band_id", rs.getString("pricing_band_id"));
                        m.put("pricing_snapshot", ps);
                    }

                    if (vaId == null) {
                        m.put("virtual_account", null);    // no VA until go-live
                    } else {
                        Map<String, Object> va = new java.util.LinkedHashMap<>();
                        va.put("account_no", rs.getString("va_account_no"));
                        va.put("ifsc", rs.getString("va_ifsc"));
                        va.put("status", rs.getString("va_status"));
                        m.put("virtual_account", va);
                    }

                    Map<String, Object> invoice = new java.util.LinkedHashMap<>();
                    invoice.put("invoice_number", rs.getString("invoice_number"));
                    invoice.put("face_value_paise", rs.getLong("face_value_paise"));
                    invoice.put("tenor_days", rs.getInt("tenor_days"));
                    invoice.put("invoice_date", rs.getObject("invoice_date", LocalDate.class));
                    invoice.put("due_date", rs.getObject("due_date", LocalDate.class));
                    invoice.put("irn", rs.getString("irn"));
                    m.put("invoice", invoice);

                    Map<String, Object> buyer = new java.util.LinkedHashMap<>();
                    buyer.put("buyer_id", rs.getObject("buyer_id", UUID.class).toString());
                    buyer.put("legal_name", rs.getString("buyer_legal_name"));
                    buyer.put("sector", rs.getString("buyer_sector"));
                    buyer.put("status", rs.getString("buyer_status"));
                    buyer.put("mca_cin", rs.getString("buyer_mca_cin"));
                    buyer.put("gstin", rs.getString("buyer_gstin"));
                    buyer.put("credit_limit_paise", rs.getObject("buyer_credit_limit_paise", Long.class));
                    m.put("buyer", buyer);

                    Map<String, Object> supplier = new java.util.LinkedHashMap<>();
                    supplier.put("supplier_id", rs.getObject("supplier_id", UUID.class).toString());
                    supplier.put("legal_name", rs.getString("supplier_legal_name"));
                    supplier.put("constitution_type", rs.getString("supplier_constitution_type"));
                    supplier.put("pan", rs.getString("supplier_pan"));
                    supplier.put("gstin", rs.getString("supplier_gstin"));
                    supplier.put("status", rs.getString("supplier_status"));
                    m.put("supplier", supplier);
                    return m;
                },
                id);
        if (detail == null) {
            throw new NotFoundException("listing not found: " + id);
        }
        return detail;
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
