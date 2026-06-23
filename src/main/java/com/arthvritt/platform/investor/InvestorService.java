package com.arthvritt.platform.investor;

import com.arthvritt.platform.adminiam.AdminRole;
import com.arthvritt.platform.adminiam.RoleResolver;
import com.arthvritt.platform.command.CommandEvent;
import com.arthvritt.platform.command.CommandGateway;
import com.arthvritt.platform.command.CommandOutcome;
import com.arthvritt.platform.command.CommandRejectedException;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.compliance.ComplianceService;
import com.arthvritt.platform.shared.Ids;
import com.arthvritt.platform.shared.error.NotFoundException;
import com.arthvritt.platform.shared.error.ValidationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BC7 Investor onboarding (WS-3, admin-on-behalf — invite-gated). The invite is issued by a
 * compliance_reviewer; sign-up consumes it (C20/DL-008) and provisions the investor identity + account.
 * The linear {@code inv_account} machine advances {@code signed_up → identity_verified → kyc_submitted →
 * suitability_assessed → financial_profile_completed → kyc_approved → mia_signed → active} (DB-enum order —
 * KYC approval follows suitability + financial profile). All commands route through {@link CommandGateway}.
 * KYC reuses {@link ComplianceService} (maker-checker). Set-once PII (pan/aadhaar/bank) is written to
 * {@code inv_account} columns but never to an audit payload. Mirrors {@code SupplierService}.
 */
@Service
public class InvestorService {

    private static final String CONTEXT = "investor";
    private static final Set<String> OPS = Set.of(AdminRole.OPS_EXECUTIVE.wire());
    private static final Set<String> COMPLIANCE = Set.of(AdminRole.COMPLIANCE_REVIEWER.wire());
    private static final Set<String> PHASE1_SUB_TYPES = Set.of("resident_individual", "huf");

    private final JdbcTemplate jdbc;
    private final CommandGateway gateway;
    private final RoleResolver roles;
    private final ComplianceService compliance;

    public InvestorService(JdbcTemplate jdbc, CommandGateway gateway, RoleResolver roles,
                           ComplianceService compliance) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.roles = roles;
        this.compliance = compliance;
    }

    /** Compliance issues an invite (C20). Stores SHA-256 of email/phone only — never the raw values. */
    public CommandResult<UUID> issueInvite(CommandRequest request, String email, String phone) {
        return gateway.execute(request, COMPLIANCE, () -> {
            UUID inviteId = request.aggregateId();
            jdbc.update("INSERT INTO inv_invite (invite_id, email_hash, phone_hash, issued_by, expiry_at, status) "
                            + "VALUES (?, ?, ?, ?, now() + interval '14 days', 'pending')",
                    inviteId, sha256(email), sha256(phone), roles.adminUserId(request.actorId()));
            CommandEvent event = new CommandEvent(CONTEXT + ".Invite.Issued", 1,
                    Map.of("invite_id", inviteId.toString()), Map.of(), Map.of("status", "pending"), true);
            return new CommandOutcome<>(inviteId, event);
        });
    }

    /** Consumes the invite (pending, unexpired, email/phone hash-match) and provisions the investor. */
    public CommandResult<UUID> signUp(CommandRequest request, UUID inviteId, String email, String phone,
                                      String subType) {
        if (!PHASE1_SUB_TYPES.contains(subType)) {
            throw new ValidationException("sub_type must be one of " + PHASE1_SUB_TYPES);
        }
        return gateway.execute(request, OPS, () -> {
            UUID investorId = request.aggregateId();
            InviteRow invite = loadInvite(inviteId);
            if (invite == null) {
                throw new NotFoundException("invite not found: " + inviteId);
            }
            if (!"pending".equals(invite.status())) {
                throw new ValidationException("invite is not pending: " + inviteId);
            }
            if (invite.expired()) {
                throw new ValidationException("invite has expired: " + inviteId);
            }
            if (!Arrays.equals(invite.emailHash(), sha256(email))
                    || !Arrays.equals(invite.phoneHash(), sha256(phone))) {
                throw new ValidationException("invite does not match the provided email/phone");
            }
            // The investor is a login principal: provision its identity directly (one envelope, no PII in
            // the audit log; DuplicateKey on the email UNIQUE → clean 400). Investor login flow is M10-full.
            UUID identityId = Ids.newId();
            try {
                jdbc.update("INSERT INTO auth_identity (identity_id, kind, email, phone_e164, display_name, status) "
                                + "VALUES (?, 'investor'::identity_kind_enum, ?, ?, ?, 'active'::identity_status_enum)",
                        identityId, email, phone, "Investor");
                jdbc.update("INSERT INTO inv_account (investor_id, identity_id, invite_id, sub_type, status) "
                                + "VALUES (?, ?, ?, ?::inv_sub_type, 'signed_up')",
                        investorId, identityId, inviteId, subType);
                int consumed = jdbc.update("UPDATE inv_invite SET status = 'consumed', consumed_at = now(), "
                                + "consumed_by_identity_id = ?, aggregate_version = aggregate_version + 1 "
                                + "WHERE invite_id = ? AND status = 'pending'",
                        identityId, inviteId);
                if (consumed != 1) { // defence-in-depth: a racing consume slipped the pending check above
                    throw new ValidationException("invite is no longer pending: " + inviteId);
                }
            } catch (DuplicateKeyException e) {
                throw new ValidationException("an account already exists for this email or invite");
            }
            CommandEvent event = new CommandEvent(CONTEXT + ".InvestorAccount.SignedUp", 1,
                    Map.of("investor_id", investorId.toString()), Map.of(), Map.of("status", "signed_up"), true);
            return new CommandOutcome<>(investorId, event);
        });
    }

    public CommandResult<Void> recordIdentityVerified(CommandRequest request, String pan, String aadhaarLast4) {
        return gateway.execute(request, OPS, () -> transition(request, "signed_up", "identity_verified",
                CONTEXT + ".InvestorAccount.IdentityVerified",
                "pan = ?::pan_type, aadhaar_last4 = ?::aadhaar_last4_type", pan, aadhaarLast4));
    }

    public CommandResult<Void> submitKyc(CommandRequest request) {
        return gateway.execute(request, OPS, () -> {
            compliance.submitKyc(request.aggregateId(), "investor", roles.adminUserId(request.actorId()));
            return transitionOutcome(request, "identity_verified", "kyc_submitted",
                    CONTEXT + ".InvestorAccount.KycSubmitted", "");
        });
    }

    public CommandResult<Void> assessSuitability(CommandRequest request) {
        return gateway.execute(request, COMPLIANCE, () -> {
            // mismatch=false → no override-ack needed (the IA.4/C21 override path is deferred).
            jdbc.update("INSERT INTO inv_suitability (assessment_id, investor_id, questionnaire_doc_hash, mismatch) "
                            + "VALUES (?, ?, ?, false)",
                    Ids.newId(), request.aggregateId(), sha256("suitability:" + request.aggregateId()));
            return transitionOutcome(request, "kyc_submitted", "suitability_assessed",
                    CONTEXT + ".InvestorAccount.SuitabilityAssessed", "");
        });
    }

    public CommandResult<Void> completeFinancialProfile(CommandRequest request, String bankAccountLast4) {
        return gateway.execute(request, OPS, () -> transition(request, "suitability_assessed",
                "financial_profile_completed", CONTEXT + ".InvestorAccount.FinancialProfileCompleted",
                "bank_account_last4 = ?", bankAccountLast4));
    }

    public CommandResult<Void> recordKycApproved(CommandRequest request) {
        return gateway.execute(request, COMPLIANCE, () -> {
            UUID approverAdminUserId = roles.adminUserId(request.actorId());
            compliance.approveKyc(request.aggregateId(), "investor", approverAdminUserId,
                    request.session().mfaAssertionId().toString());
            return transition(request, "financial_profile_completed", "kyc_approved",
                    CONTEXT + ".InvestorAccount.KycApproved",
                    "kyc_approved_by = ?, kyc_approved_at = now()", approverAdminUserId);
        });
    }

    public CommandResult<Void> recordMiaSigned(CommandRequest request) {
        return gateway.execute(request, OPS, () -> {
            UUID miaAgreementId = Ids.newId(); // M5c outcome recorded (decision A)
            return transition(request, "kyc_approved", "mia_signed", CONTEXT + ".InvestorAccount.MiaSigned",
                    "mia_agreement_id = ?, mia_signed_at = now()", miaAgreementId);
        });
    }

    public CommandResult<Void> activate(CommandRequest request) {
        // kyc_refresh_due_at = activated_at + 12 months (C17) — both via now() in one statement (stable).
        return gateway.execute(request, OPS, () -> transitionOutcome(request, "mia_signed", "active",
                CONTEXT + ".InvestorAccount.Activated",
                "activated_at = now(), kyc_refresh_due_at = now() + interval '12 months'"));
    }

    // --- shared command-handler helpers ------------------------------------------------------------

    private CommandOutcome<Void> transition(CommandRequest request, String from, String to, String eventType,
                                            String extraSet, Object... extraParams) {
        UUID investorId = request.aggregateId();
        String set = "status = '" + to + "'::inv_account_status"
                + (extraSet.isBlank() ? "" : ", " + extraSet)
                + ", aggregate_version = aggregate_version + 1";
        Object[] params = new Object[extraParams.length + 2];
        System.arraycopy(extraParams, 0, params, 0, extraParams.length);
        params[extraParams.length] = investorId;
        params[extraParams.length + 1] = request.expectedVersion();
        int updated = jdbc.update("UPDATE inv_account SET " + set
                + " WHERE investor_id = ? AND status = '" + from + "'::inv_account_status AND aggregate_version = ?",
                params);
        requireUpdated(updated, investorId, from, request.expectedVersion());
        return new CommandOutcome<>(null, new CommandEvent(eventType, request.expectedVersion() + 1,
                Map.of("investor_id", investorId.toString()),
                Map.of("status", from), Map.of("status", to), true));
    }

    private CommandOutcome<Void> transitionOutcome(CommandRequest request, String from, String to,
                                                   String eventType, String extraSet) {
        return transition(request, from, to, eventType, extraSet);
    }

    private void requireUpdated(int rows, UUID investorId, String expectedStatus, int expectedVersion) {
        if (rows == 1) {
            return;
        }
        InvRow row = load(investorId);
        if (row == null) {
            throw new NotFoundException("investor not found: " + investorId);
        }
        if (!expectedStatus.equals(row.status())) {
            throw new ValidationException("investor is not " + expectedStatus + ": " + investorId
                    + " (is " + row.status() + ")");
        }
        throw CommandRejectedException.versionConflict(expectedVersion, row.version());
    }

    private InvRow load(UUID investorId) {
        return jdbc.query("SELECT status::text AS status, aggregate_version FROM inv_account WHERE investor_id = ?",
                rs -> rs.next() ? new InvRow(rs.getString("status"), rs.getInt("aggregate_version")) : null,
                investorId);
    }

    private InviteRow loadInvite(UUID inviteId) {
        return jdbc.query("SELECT status::text AS status, email_hash, phone_hash, (expiry_at <= now()) AS expired "
                        + "FROM inv_invite WHERE invite_id = ?",
                rs -> rs.next()
                        ? new InviteRow(rs.getString("status"), rs.getBytes("email_hash"),
                                rs.getBytes("phone_hash"), rs.getBoolean("expired"))
                        : null,
                inviteId);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record InvRow(String status, int version) {
    }

    private record InviteRow(String status, byte[] emailHash, byte[] phoneHash, boolean expired) {
    }
}
