package com.arthvritt.platform.investor;

import com.arthvritt.platform.audit.Actor;
import com.arthvritt.platform.audit.AuditEnvelopes;
import com.arthvritt.platform.audit.AuditLog;
import com.arthvritt.platform.auth.ActionSensitivity;
import com.arthvritt.platform.auth.AuthSession;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.infrastructure.web.CommandResponse;
import com.arthvritt.platform.infrastructure.web.CommandResponseAssembler;
import com.arthvritt.platform.infrastructure.web.ListQuery;
import com.arthvritt.platform.infrastructure.web.RequestBodies;
import com.arthvritt.platform.shared.error.ForbiddenException;
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

import java.util.LinkedHashMap;
import java.util.List;
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
    private final AuditLog auditLog;

    public InvestorController(InvestorService investors, CommandResponseAssembler responses, JdbcTemplate jdbc,
                              AuditLog auditLog) {
        this.investors = investors;
        this.responses = responses;
        this.jdbc = jdbc;
        this.auditLog = auditLog;
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

    /**
     * BE-9 (UI_INTEGRATION_BACKEND_SPEC) — the S3 investor-invite tracker. Additive read over {@code inv_invite};
     * optional {@code status} filter ({@code pending}/{@code consumed}/{@code expired}), newest first,
     * {@code LIMIT 500}. The {@code email_hash}/{@code phone_hash} columns are PII and are <b>not</b> surfaced —
     * an invite is identified by its id and lifecycle timestamps only.
     */
    @GetMapping("/investor-invites")
    public List<Map<String, Object>> invites(@AuthenticationPrincipal AuthSession session,
                                             @RequestParam(name = "status", required = false) String status) {
        return ListQuery.from(
                        "SELECT invite_id, status::text AS status, issued_by, issued_at, expiry_at, consumed_at "
                                + "FROM inv_invite")
                .eq("status", "inv_invite_status", status)
                .query(jdbc, "ORDER BY issued_at DESC", (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("invite_id", rs.getObject("invite_id", UUID.class).toString());
                    row.put("status", rs.getString("status"));
                    row.put("issued_by", rs.getObject("issued_by", UUID.class).toString());
                    row.put("issued_at", rs.getObject("issued_at", java.time.OffsetDateTime.class));
                    row.put("expiry_at", rs.getObject("expiry_at", java.time.OffsetDateTime.class));
                    row.put("consumed_at", rs.getObject("consumed_at", java.time.OffsetDateTime.class));
                    return row;
                });
    }

    /**
     * BE-14 (S13 portfolio, M10-D A3-ii) — the investor's own subscription list + the 4 summary tiles.
     * OWN-1: an {@code investor}-kind caller (resolved server-side via {@link InvestorQueryPort}, never a
     * client-supplied id) may read only its <b>own</b> {@code investor_id} — a cross-investor read is a
     * clean 403 (DoR #3). Only an <b>admin</b> bearer (positively checked — {@code admin_user}, not
     * "absence of an inv_account") keeps the un-scoped view; any other authenticated kind that is
     * neither the owning investor nor an admin is rejected 403 too (fail-closed as new login kinds land).
     * Native SQL over {@code sub_subscription} joined to {@code deal_listing -> deal_invoice} (+ the
     * buyer/supplier counterparties, Gap G10, cross-BC so LEFT JOIN — no FK). {@code wallet_attribution}
     * (Phase 2 dormant) is never surfaced.
     */
    @GetMapping("/investors/{id}/subscriptions")
    public Map<String, Object> subscriptions(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id) {
        UUID callerInvestorId = investors.investorIdForIdentity(session.identityId()).orElse(null);
        if (callerInvestorId != null) {
            if (!callerInvestorId.equals(id)) {
                auditCrossTenantDenied(session, id, "investor");
                throw ForbiddenException.crossInvestorRead("portfolio");
            }
        } else if (!isAdmin(session.identityId())) {
            auditCrossTenantDenied(session, id, "unknown");
            throw ForbiddenException.notAuthorisedForPortfolio();
        }

        List<Map<String, Object>> rows = jdbc.query(
                "SELECT s.subscription_id, s.listing_id, s.amount, s.status::text AS status, "
                        + "b.legal_name AS buyer_name, sup.legal_name AS supplier_name, i.due_date, "
                        + "(s.distribution_outcome ->> 'gross')::bigint AS dist_gross, "
                        + "(s.distribution_outcome ->> 'tds')::bigint AS dist_tds, "
                        + "(s.distribution_outcome ->> 'fee')::bigint AS dist_fee, "
                        + "(s.distribution_outcome ->> 'net')::bigint AS dist_net "
                        + "FROM sub_subscription s "
                        + "JOIN deal_listing l ON l.listing_id = s.listing_id "
                        + "JOIN deal_invoice i ON i.invoice_id = l.invoice_id "
                        + "LEFT JOIN buyer_account b ON b.buyer_id = l.buyer_id "
                        + "LEFT JOIN sup_account sup ON sup.supplier_id = l.supplier_id "
                        + "WHERE s.investor_id = ? ORDER BY s.created_at DESC",
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("subscription_id", rs.getObject("subscription_id", UUID.class).toString());
                    row.put("listing_id", rs.getObject("listing_id", UUID.class).toString());
                    row.put("amount", rs.getLong("amount"));
                    row.put("status", rs.getString("status"));
                    row.put("buyer_name", rs.getString("buyer_name"));
                    row.put("supplier_name", rs.getString("supplier_name"));
                    row.put("due_date", rs.getObject("due_date", java.time.LocalDate.class));
                    row.put("distribution_outcome", distributionOutcome(rs));
                    return row;
                },
                id);

        Map<String, Object> summary = jdbc.query(
                "SELECT COALESCE(SUM(amount) FILTER (WHERE status NOT IN "
                        + "('closed', 'cancelled_by_investor', 'refunded', 'loss_realised')), 0)::bigint "
                        + "AS total_deployed_paise, "
                        + "COALESCE(SUM((distribution_outcome ->> 'net')::bigint), 0)::bigint AS total_returned_paise, "
                        + "COUNT(*) FILTER (WHERE status NOT IN "
                        + "('closed', 'cancelled_by_investor', 'refunded', 'loss_realised')) AS active_positions, "
                        + "COUNT(*) FILTER (WHERE distribution_outcome IS NOT NULL) AS matured_positions "
                        + "FROM sub_subscription WHERE investor_id = ?",
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    if (rs.next()) {
                        m.put("total_deployed_paise", rs.getLong("total_deployed_paise"));
                        m.put("total_returned_paise", rs.getLong("total_returned_paise"));
                        m.put("active_positions", rs.getInt("active_positions"));
                        m.put("matured_positions", rs.getInt("matured_positions"));
                    }
                    return m;
                },
                id);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", rows);
        body.put("summary", summary);
        return body;
    }

    /**
     * BE-18 Part 3 (M11-B, DoR-8): audits a denied cross-tenant portfolio read — a direct, non-command
     * {@link AuditLog#append} (no {@code command_id}, the same "append outside the gateway" shape
     * {@code SubscriptionService.confirmFromInflow} uses). Denials only; a successful own read stays
     * unaudited (M10-D DoR #3).
     */
    private void auditCrossTenantDenied(AuthSession session, UUID attemptedId, String actorKind) {
        auditLog.append(AuditEnvelopes.seed("investor", "InvestorAccount", attemptedId)
                .eventType("investor.CrossTenantReadDenied")
                .actor(new Actor(actorKind, session.identityId().toString(),
                        session.sessionId().toString(), null, null))
                .payload(Map.of("attempted_investor_id", attemptedId.toString(),
                        "endpoint", "GET /investors/{id}/subscriptions"))
                .build());
    }

    /** A positive "is this identity an admin" check (mirrors {@code SessionController}'s {@code admin_user_id} read). */
    private boolean isAdmin(UUID identityId) {
        UUID adminUserId = jdbc.query("SELECT admin_user_id FROM admin_user WHERE identity_id = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, identityId);
        return adminUserId != null;
    }

    /** Decomposes the {@code distribution_outcome} JSONB in SQL (no raw-blob pass-through) — null until set. */
    private static Map<String, Object> distributionOutcome(java.sql.ResultSet rs) throws java.sql.SQLException {
        Long gross = rs.getObject("dist_gross", Long.class);
        if (gross == null) {
            return null;
        }
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("gross", gross);
        outcome.put("tds", rs.getObject("dist_tds", Long.class));
        outcome.put("fee", rs.getObject("dist_fee", Long.class));
        outcome.put("net", rs.getObject("dist_net", Long.class));
        return outcome;
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
