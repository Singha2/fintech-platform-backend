package com.arthvritt.platform.supplier;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * BC8 supplier onboarding HTTP surface (WS-1). Thin adapters: each maps the request envelope (resolved
 * session + headers) to a {@link CommandRequest} and dispatches through {@link SupplierService} /
 * {@code CommandGateway}; the gateway enforces idempotency / MFA / SoD / audit, this controller only maps
 * HTTP ↔ command and renders the B4 §2.3 response from the appended envelope. The creating command mints
 * the {@code supplier_id} deterministically from {@code (command_id, payload)} so a replay is stable and a
 * divergent body conflicts (the WS-0 pattern).
 */
@RestController
@RequestMapping("/suppliers")
public class SupplierController {

    private static final String CONTEXT = "supplier";
    private static final String AGGREGATE_TYPE = "Supplier";

    private final SupplierService suppliers;
    private final CommandResponseAssembler responses;
    private final JdbcTemplate jdbc;

    public SupplierController(SupplierService suppliers, CommandResponseAssembler responses, JdbcTemplate jdbc) {
        this.suppliers = suppliers;
        this.responses = responses;
        this.jdbc = jdbc;
    }

    @PostMapping("/create")
    public ResponseEntity<CommandResponse> create(@AuthenticationPrincipal AuthSession session,
                                                  @RequestHeader("X-Command-Id") UUID commandId,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        String legalName = RequestBodies.requiredString(body, "legal_name");
        String constitutionType = RequestBodies.requiredString(body, "constitution_type");
        String pan = RequestBodies.requiredPan(body, "pan");
        String gstin = RequestBodies.requiredGstin(body, "gstin");
        String cin = RequestBodies.requiredString(body, "cin");
        // Derive the supplier id from the FULL body so a same-command_id replay maps to the same id, while
        // any divergent field (e.g. a corrected gstin) yields a different id → the gateway 409s the conflict.
        UUID supplierId = RequestBodies.deriveAggregateId("supplier", commandId,
                String.join(":", legalName, constitutionType, pan, gstin, cin));
        CommandRequest request = create(session, commandId, supplierId, ".Supplier.Create", 0);
        CommandResult<UUID> result = suppliers.create(request, legalName, constitutionType, pan, gstin, cin);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(responses.from(result));
    }

    @PostMapping("/{id}/grant-agency-consent")
    public CommandResponse grantAgencyConsent(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                              @RequestHeader("X-Command-Id") UUID commandId,
                                              @RequestHeader("X-Aggregate-Version") int version,
                                              @RequestBody Map<String, Object> body) {
        CommandRequest request = create(session, commandId, id, ".AgencyConsent.Grant", version);
        return responses.from(suppliers.grantAgencyConsent(request, RequestBodies.requiredStrings(body, "scope")));
    }

    @PostMapping("/{id}/record-identity-verified")
    public CommandResponse recordIdentityVerified(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                                  @RequestHeader("X-Command-Id") UUID commandId,
                                                  @RequestHeader("X-Aggregate-Version") int version) {
        return responses.from(suppliers.recordIdentityVerified(
                create(session, commandId, id, ".Supplier.RecordIdentityVerified", version)));
    }

    @PostMapping("/{id}/submit-kyc")
    public CommandResponse submitKyc(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                     @RequestHeader("X-Command-Id") UUID commandId,
                                     @RequestHeader("X-Aggregate-Version") int version) {
        return responses.from(suppliers.submitKyc(create(session, commandId, id, ".Supplier.SubmitKyc", version)));
    }

    @PostMapping("/{id}/record-kyc-approved")
    public CommandResponse recordKycApproved(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                             @RequestHeader("X-Command-Id") UUID commandId,
                                             @RequestHeader("X-Aggregate-Version") int version) {
        return responses.from(
                suppliers.recordKycApproved(create(session, commandId, id, ".Supplier.RecordKycApproved", version)));
    }

    @PostMapping("/{id}/submit-financial-profile")
    public CommandResponse submitFinancialProfile(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                                  @RequestHeader("X-Command-Id") UUID commandId,
                                                  @RequestHeader("X-Aggregate-Version") int version,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        CommandRequest request = create(session, commandId, id, ".Supplier.SubmitFinancialProfile", version);
        return responses.from(suppliers.submitFinancialProfile(request, body == null ? null : body.get("top_buyers")));
    }

    @PostMapping("/{id}/record-credit-review")
    public CommandResponse recordCreditReview(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                              @RequestHeader("X-Command-Id") UUID commandId,
                                              @RequestHeader("X-Aggregate-Version") int version,
                                              @RequestBody Map<String, Object> body) {
        CommandRequest request = create(session, commandId, id, ".Supplier.RecordCreditReview", version);
        return responses.from(suppliers.recordCreditReview(request, RequestBodies.requiredPaise(body, "exposure_cap_paise"),
                RequestBodies.requiredString(body, "risk_rating")));
    }

    @PostMapping("/{id}/record-maa-signed")
    public CommandResponse recordMaaSigned(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                           @RequestHeader("X-Command-Id") UUID commandId,
                                           @RequestHeader("X-Aggregate-Version") int version) {
        return responses.from(
                suppliers.recordMaaSigned(create(session, commandId, id, ".Supplier.RecordMaaSigned", version)));
    }

    @PostMapping("/{id}/activate")
    public CommandResponse activate(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                    @RequestHeader("X-Command-Id") UUID commandId,
                                    @RequestHeader("X-Aggregate-Version") int version) {
        return responses.from(suppliers.activate(create(session, commandId, id, ".Supplier.Activate", version)));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id) {
        Map<String, Object> row = jdbc.query(
                "SELECT supplier_id, status::text AS status, aggregate_version FROM sup_account WHERE supplier_id = ?",
                rs -> rs.next()
                        ? Map.<String, Object>of(
                                "supplier_id", rs.getObject("supplier_id", UUID.class).toString(),
                                "status", rs.getString("status"),
                                "aggregate_version", rs.getInt("aggregate_version"))
                        : null,
                id);
        if (row == null) {
            throw new NotFoundException("supplier not found: " + id);
        }
        return row;
    }

    // --- helpers -----------------------------------------------------------------------------------

    private CommandRequest create(AuthSession session, UUID commandId, UUID supplierId, String command, int version) {
        return new CommandRequest(session, commandId, CONTEXT, CONTEXT + command, AGGREGATE_TYPE, supplierId,
                version, "admin_user", ActionSensitivity.SENSITIVE);
    }
}
