package com.arthvritt.platform.investor;

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

import java.util.Map;
import java.util.UUID;

/**
 * BC7 investor onboarding HTTP surface (WS-3). Thin adapters mapping the request envelope to a
 * {@link CommandRequest} and dispatching through {@link InvestorService}/{@code CommandGateway}. The invite
 * + sign-up are creating commands (201); the rest advance the linear machine. Mirrors the supplier/buyer
 * controllers and reuses the shared {@link RequestBodies} validation.
 */
@RestController
public class InvestorController {

    private static final String CONTEXT = "investor";

    private final InvestorService investors;
    private final CommandResponseAssembler responses;
    private final JdbcTemplate jdbc;

    public InvestorController(InvestorService investors, CommandResponseAssembler responses, JdbcTemplate jdbc) {
        this.investors = investors;
        this.responses = responses;
        this.jdbc = jdbc;
    }

    @PostMapping("/investor-invites/issue")
    public ResponseEntity<CommandResponse> issueInvite(@AuthenticationPrincipal AuthSession session,
                                                       @RequestHeader("X-Command-Id") UUID commandId,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        String email = RequestBodies.requiredString(body, "email");
        String phone = RequestBodies.requiredString(body, "phone");
        UUID inviteId = RequestBodies.deriveAggregateId("invite", commandId, String.join(":", email, phone));
        CommandRequest request = command(session, commandId, "Invite", inviteId, ".Invite.Issue", 0);
        CommandResult<UUID> result = investors.issueInvite(request, email, phone);
        return created(result);
    }

    @PostMapping("/investors/sign-up")
    public ResponseEntity<CommandResponse> signUp(@AuthenticationPrincipal AuthSession session,
                                                  @RequestHeader("X-Command-Id") UUID commandId,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        String inviteId = RequestBodies.requiredString(body, "invite_id");
        String email = RequestBodies.requiredString(body, "email");
        String phone = RequestBodies.requiredString(body, "phone");
        String subType = RequestBodies.requiredString(body, "sub_type");
        // One account per invite (UNIQUE) — derive the investor id from the invite so a replay is stable.
        UUID investorId = RequestBodies.deriveAggregateId("investor", commandId, inviteId);
        CommandRequest request = command(session, commandId, "InvestorAccount", investorId, ".InvestorAccount.SignUp", 0);
        CommandResult<UUID> result = investors.signUp(request, uuid(inviteId), email, phone, subType);
        return created(result);
    }

    @PostMapping("/investors/{id}/record-identity-verified")
    public CommandResponse recordIdentityVerified(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                                  @RequestHeader("X-Command-Id") UUID commandId,
                                                  @RequestHeader("X-Aggregate-Version") int version,
                                                  @RequestBody Map<String, Object> body) {
        CommandRequest request = command(session, commandId, "InvestorAccount", id,
                ".InvestorAccount.RecordIdentityVerified", version);
        return responses.from(investors.recordIdentityVerified(request,
                RequestBodies.requiredPan(body, "pan"), RequestBodies.requiredFourDigits(body, "aadhaar_last4")));
    }

    @PostMapping("/investors/{id}/submit-kyc")
    public CommandResponse submitKyc(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                     @RequestHeader("X-Command-Id") UUID commandId,
                                     @RequestHeader("X-Aggregate-Version") int version) {
        return responses.from(investors.submitKyc(
                command(session, commandId, "InvestorAccount", id, ".InvestorAccount.SubmitKyc", version)));
    }

    @PostMapping("/investors/{id}/assess-suitability")
    public CommandResponse assessSuitability(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                             @RequestHeader("X-Command-Id") UUID commandId,
                                             @RequestHeader("X-Aggregate-Version") int version,
                                             @RequestBody(required = false) Map<String, Object> body) {
        boolean mismatch = body != null && Boolean.TRUE.equals(body.get("mismatch"));
        CommandRequest request = command(session, commandId, "InvestorAccount", id,
                ".InvestorAccount.AssessSuitability", version);
        return responses.from(investors.assessSuitability(request, mismatch));
    }

    @PostMapping("/investors/{id}/acknowledge-suitability-override")
    public CommandResponse acknowledgeSuitabilityOverride(@AuthenticationPrincipal AuthSession session,
                                                          @PathVariable UUID id,
                                                          @RequestHeader("X-Command-Id") UUID commandId,
                                                          @RequestHeader("X-Aggregate-Version") int version,
                                                          @RequestBody Map<String, Object> body) {
        String overrideText = RequestBodies.requiredString(body, "override_text");
        CommandRequest request = command(session, commandId, "InvestorAccount", id,
                ".InvestorAccount.AcknowledgeSuitabilityOverride", version);
        return responses.from(investors.acknowledgeSuitabilityOverride(request, overrideText));
    }

    @PostMapping("/investors/{id}/complete-financial-profile")
    public CommandResponse completeFinancialProfile(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                                    @RequestHeader("X-Command-Id") UUID commandId,
                                                    @RequestHeader("X-Aggregate-Version") int version,
                                                    @RequestBody Map<String, Object> body) {
        CommandRequest request = command(session, commandId, "InvestorAccount", id,
                ".InvestorAccount.CompleteFinancialProfile", version);
        return responses.from(investors.completeFinancialProfile(request,
                RequestBodies.requiredFourDigits(body, "bank_account_last4")));
    }

    @PostMapping("/investors/{id}/record-kyc-approved")
    public CommandResponse recordKycApproved(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                             @RequestHeader("X-Command-Id") UUID commandId,
                                             @RequestHeader("X-Aggregate-Version") int version) {
        return responses.from(investors.recordKycApproved(
                command(session, commandId, "InvestorAccount", id, ".InvestorAccount.RecordKycApproved", version)));
    }

    @PostMapping("/investors/{id}/record-kyc-rejected")
    public CommandResponse recordKycRejected(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                             @RequestHeader("X-Command-Id") UUID commandId,
                                             @RequestHeader("X-Aggregate-Version") int version,
                                             @RequestBody Map<String, Object> body) {
        String reason = RequestBodies.requiredString(body, "reason");
        CommandRequest request = command(session, commandId, "InvestorAccount", id,
                ".InvestorAccount.RecordKycRejected", version);
        return responses.from(investors.recordKycRejected(request, reason));
    }

    @PostMapping("/investors/{id}/resubmit-kyc")
    public CommandResponse resubmitKyc(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                       @RequestHeader("X-Command-Id") UUID commandId,
                                       @RequestHeader("X-Aggregate-Version") int version) {
        return responses.from(investors.resubmitKyc(
                command(session, commandId, "InvestorAccount", id, ".InvestorAccount.ResubmitKyc", version)));
    }

    @PostMapping("/investors/{id}/record-mia-signed")
    public CommandResponse recordMiaSigned(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                           @RequestHeader("X-Command-Id") UUID commandId,
                                           @RequestHeader("X-Aggregate-Version") int version) {
        return responses.from(investors.recordMiaSigned(
                command(session, commandId, "InvestorAccount", id, ".InvestorAccount.RecordMiaSigned", version)));
    }

    @PostMapping("/investors/{id}/activate")
    public CommandResponse activate(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                    @RequestHeader("X-Command-Id") UUID commandId,
                                    @RequestHeader("X-Aggregate-Version") int version) {
        return responses.from(investors.activate(
                command(session, commandId, "InvestorAccount", id, ".InvestorAccount.Activate", version)));
    }

    @GetMapping("/investors/{id}")
    public Map<String, Object> get(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id) {
        Map<String, Object> row = jdbc.query(
                "SELECT investor_id, status::text AS status, aggregate_version FROM inv_account WHERE investor_id = ?",
                rs -> rs.next()
                        ? Map.<String, Object>of(
                                "investor_id", rs.getObject("investor_id", UUID.class).toString(),
                                "status", rs.getString("status"),
                                "aggregate_version", rs.getInt("aggregate_version"))
                        : null,
                id);
        if (row == null) {
            throw new NotFoundException("investor not found: " + id);
        }
        return row;
    }

    private ResponseEntity<CommandResponse> created(CommandResult<UUID> result) {
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(responses.from(result));
    }

    private CommandRequest command(AuthSession session, UUID commandId, String aggregateType, UUID aggregateId,
                                   String name, int version) {
        return new CommandRequest(session, commandId, CONTEXT, CONTEXT + name, aggregateType, aggregateId,
                version, "admin_user", ActionSensitivity.SENSITIVE);
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("invite_id is not a valid id");
        }
    }
}
