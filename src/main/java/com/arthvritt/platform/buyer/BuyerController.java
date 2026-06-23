package com.arthvritt.platform.buyer;

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
 * BC9 buyer onboarding HTTP surface (WS-2). Thin adapters mapping the request envelope to a
 * {@link CommandRequest} and dispatching through {@link BuyerService}/{@code CommandGateway}; the gateway
 * enforces idempotency / MFA / SoD / audit. Mirrors {@code SupplierController} and reuses the shared
 * {@link RequestBodies} validation + idempotent-create derivation.
 */
@RestController
@RequestMapping("/buyers")
public class BuyerController {

    private static final String CONTEXT = "buyer";
    private static final String AGGREGATE_TYPE = "Buyer";

    private final BuyerService buyers;
    private final CommandResponseAssembler responses;
    private final JdbcTemplate jdbc;

    public BuyerController(BuyerService buyers, CommandResponseAssembler responses, JdbcTemplate jdbc) {
        this.buyers = buyers;
        this.responses = responses;
        this.jdbc = jdbc;
    }

    @PostMapping("/nominate")
    public ResponseEntity<CommandResponse> nominate(@AuthenticationPrincipal AuthSession session,
                                                    @RequestHeader("X-Command-Id") UUID commandId,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        String legalName = RequestBodies.requiredString(body, "legal_name");
        String mcaCin = RequestBodies.requiredString(body, "mca_cin");
        String gstin = RequestBodies.requiredGstin(body, "gstin");
        String sector = RequestBodies.requiredString(body, "sector");
        UUID buyerId = RequestBodies.deriveAggregateId("buyer", commandId,
                String.join(":", legalName, mcaCin, gstin, sector));
        CommandRequest request = command(session, commandId, buyerId, ".Buyer.Nominate", 0);
        CommandResult<UUID> result = buyers.nominate(request, legalName, mcaCin, gstin, sector);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(responses.from(result));
    }

    @PostMapping("/{id}/record-identity-verified")
    public CommandResponse recordIdentityVerified(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                                  @RequestHeader("X-Command-Id") UUID commandId,
                                                  @RequestHeader("X-Aggregate-Version") int version) {
        return responses.from(buyers.recordIdentityVerified(
                command(session, commandId, id, ".Buyer.RecordIdentityVerified", version)));
    }

    @PostMapping("/{id}/record-credit-assessment")
    public CommandResponse recordCreditAssessment(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                                  @RequestHeader("X-Command-Id") UUID commandId,
                                                  @RequestHeader("X-Aggregate-Version") int version,
                                                  @RequestBody Map<String, Object> body) {
        CommandRequest request = command(session, commandId, id, ".Buyer.RecordCreditAssessment", version);
        return responses.from(buyers.recordCreditAssessment(request,
                RequestBodies.requiredPositivePaise(body, "credit_limit_paise")));
    }

    @PostMapping("/{id}/start-engagement")
    public CommandResponse startEngagement(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                           @RequestHeader("X-Command-Id") UUID commandId,
                                           @RequestHeader("X-Aggregate-Version") int version) {
        return responses.from(buyers.startEngagement(command(session, commandId, id, ".Buyer.StartEngagement", version)));
    }

    @PostMapping("/{id}/designate-ack-user")
    public CommandResponse designateAckUser(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                            @RequestHeader("X-Command-Id") UUID commandId,
                                            @RequestHeader("X-Aggregate-Version") int version,
                                            @RequestBody Map<String, Object> body) {
        CommandRequest request = command(session, commandId, id, ".Buyer.DesignateAckUser", version);
        return responses.from(buyers.designateAckUser(request, RequestBodies.requiredString(body, "email"),
                RequestBodies.requiredString(body, "phone"), RequestBodies.requiredString(body, "display_name")));
    }

    @PostMapping("/{id}/confirm-payment-instruction")
    public CommandResponse confirmPaymentInstruction(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                                     @RequestHeader("X-Command-Id") UUID commandId,
                                                     @RequestHeader("X-Aggregate-Version") int version) {
        return responses.from(buyers.confirmPaymentInstruction(
                command(session, commandId, id, ".Buyer.ConfirmPaymentInstruction", version)));
    }

    @PostMapping("/{id}/activate")
    public CommandResponse activate(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                    @RequestHeader("X-Command-Id") UUID commandId,
                                    @RequestHeader("X-Aggregate-Version") int version) {
        return responses.from(buyers.activate(command(session, commandId, id, ".Buyer.Activate", version)));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id) {
        Map<String, Object> row = jdbc.query(
                "SELECT buyer_id, status::text AS status, aggregate_version FROM buyer_account WHERE buyer_id = ?",
                rs -> rs.next()
                        ? Map.<String, Object>of(
                                "buyer_id", rs.getObject("buyer_id", UUID.class).toString(),
                                "status", rs.getString("status"),
                                "aggregate_version", rs.getInt("aggregate_version"))
                        : null,
                id);
        if (row == null) {
            throw new NotFoundException("buyer not found: " + id);
        }
        return row;
    }

    private CommandRequest command(AuthSession session, UUID commandId, UUID buyerId, String name, int version) {
        return new CommandRequest(session, commandId, CONTEXT, CONTEXT + name, AGGREGATE_TYPE, buyerId,
                version, "admin_user", ActionSensitivity.SENSITIVE);
    }
}
