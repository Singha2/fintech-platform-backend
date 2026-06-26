package com.arthvritt.platform.buyer;

import com.arthvritt.platform.adminiam.AdminRole;
import com.arthvritt.platform.adminiam.RoleResolver;
import com.arthvritt.platform.command.CommandEvent;
import com.arthvritt.platform.command.CommandGateway;
import com.arthvritt.platform.command.CommandOutcome;
import com.arthvritt.platform.command.CommandRejectedException;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.shared.Ids;
import com.arthvritt.platform.shared.error.NotFoundException;
import com.arthvritt.platform.shared.error.ValidationException;
import com.arthvritt.platform.verification.VerificationPort;
import com.arthvritt.platform.verification.VerificationResult;
import com.arthvritt.platform.verification.VerificationStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BC9 Buyer onboarding (WS-2, admin-on-behalf — DL-012). Seven commands through the {@link CommandGateway}
 * (idempotency #4, MFA-fresh #2, SoD #3, audit #5), advancing the linear {@code buyer_account} machine
 * {@code nominated → identity_verified → credit_assessed → engagement_started → active}. Each transition is
 * a status-guarded optimistic UPDATE; the {@code active} step additionally enforces the BA.3 gate (≥1
 * active ack user ∧ a current payment rule). The acknowledgment user is a login principal — designation
 * provisions an {@code acknowledgment_user} identity (OTP-only, no password/MFA — AU.1/DL-021). Mirrors
 * {@code SupplierService}; native SQL onto the V2 tables.
 */
@Service
public class BuyerService {

    private static final String CONTEXT = "buyer";
    private static final Set<String> OPS = Set.of(AdminRole.OPS_EXECUTIVE.wire());
    private static final Set<String> CREDIT = Set.of(AdminRole.CREDIT_REVIEWER.wire());

    private final JdbcTemplate jdbc;
    private final CommandGateway gateway;
    private final RoleResolver roles;
    private final VerificationPort verification;

    public BuyerService(JdbcTemplate jdbc, CommandGateway gateway, RoleResolver roles, VerificationPort verification) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.roles = roles;
        this.verification = verification;
    }

    public CommandResult<UUID> nominate(CommandRequest request, String legalName, String mcaCin,
                                        String gstin, String sector) {
        return gateway.execute(request, CREDIT, () -> {
            UUID buyerId = request.aggregateId();
            jdbc.update("INSERT INTO buyer_account (buyer_id, legal_name, mca_cin, gstin, sector, status, nominated_by) "
                            + "VALUES (?, ?, ?, ?::gstin_type, ?, 'nominated', ?)",
                    buyerId, legalName, mcaCin, gstin, sector, roles.adminUserId(request.actorId()));
            CommandEvent event = new CommandEvent(CONTEXT + ".Buyer.Nominated", 1,
                    Map.of("buyer_id", buyerId.toString(), "legal_name", legalName),
                    Map.of(), Map.of("status", "nominated"), true);
            return new CommandOutcome<>(buyerId, event);
        });
    }

    public CommandResult<Void> recordIdentityVerified(CommandRequest request) {
        return gateway.execute(request, OPS, () -> {
            UUID buyerId = request.aggregateId();
            // BA.4/C24: the buyer's GSTIN + CIN (captured at nominate) are verified through the BC17 ACL,
            // not self-attested (mirrors M7-A; the buyer has no PAN). Both are NOT NULL at nominate.
            Identity id = loadIdentity(buyerId);
            requireVerified(verification.verifyGstin(buyerId, id.gstin()), "gstin_status", "ACTIVE", "GSTIN");
            requireVerified(verification.fetchMca21(buyerId, id.mcaCin()), "cin_valid", "true", "CIN (MCA21)");
            return transitionOutcome(request, "nominated", "identity_verified",
                    CONTEXT + ".Buyer.IdentityVerified", "");
        });
    }

    /**
     * Rejects (422 {@code verification_failed}) unless the ACL result is COMPLETED and its {@code field}
     * (string- or boolean-valued) equals {@code expected}. Fail-closed: a null/missing field never passes.
     */
    private void requireVerified(VerificationResult result, String field, String expected, String label) {
        Object raw = result.extractedFields() == null ? null : result.extractedFields().get(field);
        String value = raw == null ? null : String.valueOf(raw);
        if (result.status() != VerificationStatus.COMPLETED || !expected.equals(value)) {
            throw CommandRejectedException.verificationFailed(label);
        }
    }

    private Identity loadIdentity(UUID buyerId) {
        Identity id = jdbc.query("SELECT gstin::text AS gstin, mca_cin FROM buyer_account WHERE buyer_id = ?",
                rs -> rs.next() ? new Identity(rs.getString("gstin"), rs.getString("mca_cin")) : null, buyerId);
        if (id == null) {
            throw new NotFoundException("buyer not found: " + buyerId);
        }
        return id;
    }

    public CommandResult<Void> recordCreditAssessment(CommandRequest request, long creditLimitPaise) {
        return gateway.execute(request, CREDIT, () -> transition(request, "identity_verified", "credit_assessed",
                CONTEXT + ".Buyer.CreditAssessed", "credit_limit_paise = ?", creditLimitPaise));
    }

    public CommandResult<Void> startEngagement(CommandRequest request) {
        return gateway.execute(request, OPS, () -> transitionOutcome(request, "credit_assessed", "engagement_started",
                CONTEXT + ".Buyer.EngagementStarted", ""));
    }

    public CommandResult<Void> designateAckUser(CommandRequest request, String email, String phone,
                                                String displayName) {
        return gateway.execute(request, OPS, () -> {
            UUID buyerId = request.aggregateId();
            BuyerRow row = loadExisting(buyerId);
            // The ack user is a login principal: provision its acknowledgment_user identity directly (OTP-only
            // — no password, no MFA factor; DL-021). A direct insert (vs AuthService.provisionIdentity) keeps
            // this command to ONE audit envelope and keeps the ack user's email/phone (PII) out of the audit
            // log — the buyer.AckUser.Designated envelope references only the identity id.
            UUID ackIdentityId = Ids.newId();
            try {
                jdbc.update("INSERT INTO auth_identity (identity_id, kind, email, phone_e164, display_name, status) "
                                + "VALUES (?, 'acknowledgment_user'::identity_kind_enum, ?, ?, ?, "
                                + "'active'::identity_status_enum)",
                        ackIdentityId, email, phone, displayName);
                jdbc.update("INSERT INTO buyer_ack_user "
                                + "(ack_user_id, buyer_id, identity_id, display_name, email, phone, designated_by) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                        Ids.newId(), buyerId, ackIdentityId, displayName, email, phone,
                        roles.adminUserId(request.actorId()));
            } catch (DuplicateKeyException e) {
                throw new ValidationException("an acknowledgment user with this email already exists: " + email);
            }
            return new CommandOutcome<>(null, nonTransition(buyerId, CONTEXT + ".AckUser.Designated",
                    row.version(), Map.of("ack_identity_id", ackIdentityId.toString())));
        });
    }

    public CommandResult<Void> confirmPaymentInstruction(CommandRequest request) {
        return gateway.execute(request, OPS, () -> {
            UUID buyerId = request.aggregateId();
            BuyerRow row = loadExisting(buyerId);
            // One current rule per buyer (partial UNIQUE) — surface a re-confirm as a clean 400, not a 500.
            if (hasCurrentPaymentRule(buyerId)) {
                throw new ValidationException("a current payment instruction already exists for buyer: " + buyerId);
            }
            jdbc.update("INSERT INTO buyer_payment_rule "
                            + "(instruction_id, buyer_id, instruction_doc_hash, effective_from, confirmed_by) "
                            + "VALUES (?, ?, ?, now()::date, ?)",
                    Ids.newId(), buyerId, sha256("payment:" + buyerId), roles.adminUserId(request.actorId()));
            return new CommandOutcome<>(null, nonTransition(buyerId, CONTEXT + ".PaymentInstruction.Confirmed",
                    row.version(), Map.of()));
        });
    }

    public CommandResult<Void> activate(CommandRequest request) {
        return gateway.execute(request, OPS, () -> {
            UUID buyerId = request.aggregateId();
            // State first (so a wrong-state buyer gets a clear "not engagement_started", not a BA.3 message),
            // then the BA.3 gate, then the version-guarded transition.
            BuyerRow row = loadExisting(buyerId);
            if (!"engagement_started".equals(row.status())) {
                throw new ValidationException("buyer is not engagement_started: " + buyerId
                        + " (is " + row.status() + ")");
            }
            requireBa3(buyerId);
            return transitionOutcome(request, "engagement_started", "active", CONTEXT + ".Buyer.Activated",
                    "activated_at = now()");
        });
    }

    // --- shared command-handler helpers ------------------------------------------------------------

    /** BA.3: an active ack user AND a current payment rule are required before activation. */
    private void requireBa3(UUID buyerId) {
        if (!hasActiveAckUser(buyerId)) {
            throw new ValidationException("BA.3: an active acknowledgment user is required before activation");
        }
        if (!hasCurrentPaymentRule(buyerId)) {
            throw new ValidationException("BA.3: a confirmed payment instruction is required before activation");
        }
    }

    private CommandOutcome<Void> transition(CommandRequest request, String from, String to, String eventType,
                                            String extraSet, Object... extraParams) {
        UUID buyerId = request.aggregateId();
        String set = "status = '" + to + "'::buyer_account_status"
                + (extraSet.isBlank() ? "" : ", " + extraSet)
                + ", aggregate_version = aggregate_version + 1";
        Object[] params = new Object[extraParams.length + 2];
        System.arraycopy(extraParams, 0, params, 0, extraParams.length);
        params[extraParams.length] = buyerId;
        params[extraParams.length + 1] = request.expectedVersion();
        int updated = jdbc.update("UPDATE buyer_account SET " + set
                + " WHERE buyer_id = ? AND status = '" + from + "'::buyer_account_status AND aggregate_version = ?",
                params);
        requireUpdated(updated, buyerId, from, request.expectedVersion());
        return new CommandOutcome<>(null, new CommandEvent(eventType, request.expectedVersion() + 1,
                Map.of("buyer_id", buyerId.toString()),
                Map.of("status", from), Map.of("status", to), true));
    }

    private CommandOutcome<Void> transitionOutcome(CommandRequest request, String from, String to,
                                                   String eventType, String extraSet) {
        return transition(request, from, to, eventType, extraSet);
    }

    private CommandEvent nonTransition(UUID buyerId, String eventType, int currentVersion,
                                       Map<String, Object> payload) {
        Map<String, Object> body = new java.util.LinkedHashMap<>(payload);
        body.put("buyer_id", buyerId.toString());
        return new CommandEvent(eventType, currentVersion, body, null, null, false);
    }

    private void requireUpdated(int rows, UUID buyerId, String expectedStatus, int expectedVersion) {
        if (rows == 1) {
            return;
        }
        BuyerRow row = load(buyerId);
        if (row == null) {
            throw new NotFoundException("buyer not found: " + buyerId);
        }
        if (!expectedStatus.equals(row.status())) {
            throw new ValidationException("buyer is not " + expectedStatus + ": " + buyerId
                    + " (is " + row.status() + ")");
        }
        throw CommandRejectedException.versionConflict(expectedVersion, row.version());
    }

    private boolean hasActiveAckUser(UUID buyerId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM buyer_ack_user WHERE buyer_id = ? AND is_active = TRUE", Integer.class, buyerId);
        return n != null && n > 0;
    }

    private boolean hasCurrentPaymentRule(UUID buyerId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM buyer_payment_rule WHERE buyer_id = ? AND superseded_by IS NULL",
                Integer.class, buyerId);
        return n != null && n > 0;
    }

    private BuyerRow loadExisting(UUID buyerId) {
        BuyerRow row = load(buyerId);
        if (row == null) {
            throw new NotFoundException("buyer not found: " + buyerId);
        }
        return row;
    }

    private BuyerRow load(UUID buyerId) {
        return jdbc.query("SELECT status::text AS status, aggregate_version FROM buyer_account WHERE buyer_id = ?",
                rs -> rs.next() ? new BuyerRow(rs.getString("status"), rs.getInt("aggregate_version")) : null,
                buyerId);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record BuyerRow(String status, int version) {
    }

    private record Identity(String gstin, String mcaCin) {
    }
}
