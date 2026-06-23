package com.arthvritt.platform.supplier;

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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BC8 Supplier onboarding (WS-1, admin-on-behalf — DL-012). Nine commands, all issued through the
 * {@link CommandGateway} (so each inherits idempotency #4, MFA-freshness #2, SoD authz #3, and audit #5),
 * advancing the linear {@code sup_account} state machine
 * {@code created → identity_verified → kyc_submitted → kyc_approved → credit_reviewed → maa_signed → active}.
 * Each transition's optimistic UPDATE is guarded {@code WHERE status = <prior> AND aggregate_version = ?},
 * so a step cannot run before its predecessor — that guard is the SA8.2 activation gate. Native SQL onto
 * the V2 tables; the M5a verify / M5c sign outcomes are <i>recorded</i> here (DL-BE-031, decision A).
 */
@Service
public class SupplierService {

    private static final String CONTEXT = "supplier";
    private static final Set<String> OPS = Set.of(AdminRole.OPS_EXECUTIVE.wire());
    private static final Set<String> COMPLIANCE = Set.of(AdminRole.COMPLIANCE_REVIEWER.wire());
    private static final Set<String> CREDIT = Set.of(AdminRole.CREDIT_REVIEWER.wire());

    private final JdbcTemplate jdbc;
    private final CommandGateway gateway;
    private final ComplianceService compliance;
    private final RoleResolver roles;
    private final ObjectMapper mapper;

    public SupplierService(JdbcTemplate jdbc, CommandGateway gateway, ComplianceService compliance,
                           RoleResolver roles, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.compliance = compliance;
        this.roles = roles;
        this.mapper = mapper;
    }

    public CommandResult<UUID> create(CommandRequest request, String legalName, String constitutionType,
                                      String pan, String gstin, String cin) {
        return gateway.execute(request, OPS, () -> {
            UUID supplierId = request.aggregateId();
            jdbc.update("INSERT INTO sup_account (supplier_id, legal_name, constitution_type, pan, gstin, cin, status) "
                            + "VALUES (?, ?, ?::sup_constitution_type, ?::pan_type, ?::gstin_type, ?, 'created')",
                    supplierId, legalName, constitutionType, pan, gstin, cin);
            CommandEvent event = new CommandEvent(CONTEXT + ".Supplier.Created", 1,
                    Map.of("supplier_id", supplierId.toString(), "legal_name", legalName),
                    Map.of(), Map.of("status", "created"), true);
            return new CommandOutcome<>(supplierId, event);
        });
    }

    public CommandResult<Void> grantAgencyConsent(CommandRequest request, List<String> scope) {
        return gateway.execute(request, OPS, () -> {
            UUID supplierId = request.aggregateId();
            SupRow row = loadActiveable(supplierId);
            // Idempotent (AC.1): one active consent per supplier. A second grant is a no-op — the on-behalf
            // steps already have the consent they reference.
            boolean alreadyActive = hasActiveConsent(supplierId);
            if (!alreadyActive) {
                // consent_doc_hash references the signed consent document; WS-1 records a deterministic stub
                // hash (no real doc store yet). scope is a typed text[] param (never a built literal).
                byte[] docHash = sha256("consent:" + supplierId);
                jdbc.update(con -> {
                    PreparedStatement ps = con.prepareStatement("INSERT INTO sup_agency_consent "
                            + "(consent_id, supplier_id, scope, consent_doc_hash) VALUES (?, ?, ?, ?)");
                    ps.setObject(1, Ids.newId());
                    ps.setObject(2, supplierId);
                    ps.setArray(3, con.createArrayOf("text", scope.toArray()));
                    ps.setBytes(4, docHash);
                    return ps;
                });
            }
            return new CommandOutcome<>(null, nonTransition(supplierId, CONTEXT + ".AgencyConsent.Granted",
                    row.version(), Map.of("scope", scope, "already_active", alreadyActive)));
        });
    }

    public CommandResult<Void> recordIdentityVerified(CommandRequest request) {
        // Decision A: identity (pan/gstin/cin) is captured at create; this records the M5a verification
        // *outcome* by confirming the transition created → identity_verified.
        return gateway.execute(request, OPS, () -> transitionOutcome(request, "created", "identity_verified",
                CONTEXT + ".Supplier.IdentityVerified", ""));
    }

    public CommandResult<Void> submitKyc(CommandRequest request) {
        return gateway.execute(request, OPS, () -> {
            // The ops submitter is the maker; KYC approval (below) is DB-enforced maker-checker (KF.2/C4).
            compliance.submitKyc(request.aggregateId(), "supplier", roles.adminUserId(request.actorId()));
            return transitionOutcome(request, "identity_verified", "kyc_submitted",
                    CONTEXT + ".Supplier.KycSubmitted", "");
        });
    }

    public CommandResult<Void> recordKycApproved(CommandRequest request) {
        return gateway.execute(request, COMPLIANCE, () -> {
            UUID approverAdminUserId = roles.adminUserId(request.actorId());
            // checker ≠ maker and a fresh MFA assertion — both DB-enforced on comp_kyc_file.
            compliance.approveKyc(request.aggregateId(), "supplier", approverAdminUserId,
                    request.session().mfaAssertionId().toString());
            return transition(request, "kyc_submitted", "kyc_approved", CONTEXT + ".Supplier.KycApproved",
                    "kyc_approved_by = ?, kyc_approved_at = now()", approverAdminUserId);
        });
    }

    public CommandResult<Void> submitFinancialProfile(CommandRequest request, Object topBuyers) {
        return gateway.execute(request, OPS, () -> {
            UUID supplierId = request.aggregateId();
            SupRow row = loadActiveable(supplierId);
            // One financial profile per supplier (DB UNIQUE) — surface a re-submit as a clean 400, not a 500.
            Integer existing = jdbc.queryForObject(
                    "SELECT count(*) FROM sup_financial_profile WHERE supplier_id = ?", Integer.class, supplierId);
            if (existing != null && existing > 0) {
                throw new ValidationException("financial profile already submitted for supplier: " + supplierId);
            }
            jdbc.update("INSERT INTO sup_financial_profile (financial_profile_id, supplier_id, top_buyers) "
                            + "VALUES (?, ?, ?::jsonb)",
                    Ids.newId(), supplierId, toJson(topBuyers == null ? List.of() : topBuyers));
            return new CommandOutcome<>(null, nonTransition(supplierId,
                    CONTEXT + ".Supplier.FinancialProfileSubmitted", row.version(), Map.of()));
        });
    }

    public CommandResult<Void> recordCreditReview(CommandRequest request, long exposureCapPaise, String riskRating) {
        return gateway.execute(request, CREDIT, () -> transition(request, "kyc_approved", "credit_reviewed",
                CONTEXT + ".Supplier.CreditReviewed",
                "credit_exposure_cap_paise = ?, credit_risk_rating = ?", exposureCapPaise, riskRating));
    }

    public CommandResult<Void> recordMaaSigned(CommandRequest request) {
        return gateway.execute(request, OPS, () -> {
            // Decision A: the MAA was signed via M5c elsewhere; WS-1 records the agreement id + timestamp.
            UUID maaAgreementId = Ids.newId();
            return transition(request, "credit_reviewed", "maa_signed", CONTEXT + ".Supplier.MaaSigned",
                    "maa_agreement_id = ?, maa_signed_at = now()", maaAgreementId);
        });
    }

    public CommandResult<Void> activate(CommandRequest request) {
        return gateway.execute(request, OPS, () -> transitionOutcome(request, "maa_signed", "active",
                CONTEXT + ".Supplier.Activated", "activated_at = now()"));
    }

    // --- shared command-handler helpers ------------------------------------------------------------

    private CommandOutcome<Void> transition(CommandRequest request, String from, String to, String eventType,
                                            String extraSet, Object... extraParams) {
        UUID supplierId = request.aggregateId();
        String set = "status = '" + to + "'::sup_account_status"
                + (extraSet.isBlank() ? "" : ", " + extraSet)
                + ", aggregate_version = aggregate_version + 1";
        Object[] params = new Object[extraParams.length + 2];
        System.arraycopy(extraParams, 0, params, 0, extraParams.length);
        params[extraParams.length] = supplierId;
        params[extraParams.length + 1] = request.expectedVersion();
        int updated = jdbc.update("UPDATE sup_account SET " + set
                + " WHERE supplier_id = ? AND status = '" + from + "'::sup_account_status AND aggregate_version = ?",
                params);
        requireUpdated(updated, supplierId, from, request.expectedVersion());
        int newVersion = request.expectedVersion() + 1;
        return new CommandOutcome<>(null, new CommandEvent(eventType, newVersion,
                Map.of("supplier_id", supplierId.toString()),
                Map.of("status", from), Map.of("status", to), true));
    }

    /** {@link #transition} with no command-supplied params (the extra-set clause is a literal). */
    private CommandOutcome<Void> transitionOutcome(CommandRequest request, String from, String to,
                                                   String eventType, String extraSet) {
        return transition(request, from, to, eventType, extraSet);
    }

    private CommandEvent nonTransition(UUID supplierId, String eventType, int currentVersion,
                                       Map<String, Object> payload) {
        Map<String, Object> body = new java.util.LinkedHashMap<>(payload);
        body.put("supplier_id", supplierId.toString());
        return new CommandEvent(eventType, currentVersion, body, null, null, false);
    }

    private void requireUpdated(int rows, UUID supplierId, String expectedStatus, int expectedVersion) {
        if (rows == 1) {
            return;
        }
        SupRow row = load(supplierId);
        if (row == null) {
            throw new NotFoundException("supplier not found: " + supplierId);
        }
        if (!expectedStatus.equals(row.status())) {
            throw new ValidationException("supplier is not " + expectedStatus + ": " + supplierId
                    + " (is " + row.status() + ")");
        }
        throw CommandRejectedException.versionConflict(expectedVersion, row.version());
    }

    private boolean hasActiveConsent(UUID supplierId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM sup_agency_consent WHERE supplier_id = ? AND is_active = TRUE",
                Integer.class, supplierId);
        return n != null && n > 0;
    }

    private SupRow loadActiveable(UUID supplierId) {
        SupRow row = load(supplierId);
        if (row == null) {
            throw new NotFoundException("supplier not found: " + supplierId);
        }
        return row;
    }

    private SupRow load(UUID supplierId) {
        return jdbc.query("SELECT status::text AS status, aggregate_version FROM sup_account WHERE supplier_id = ?",
                rs -> rs.next() ? new SupRow(rs.getString("status"), rs.getInt("aggregate_version")) : null,
                supplierId);
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new ValidationException("invalid JSON payload");
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record SupRow(String status, int version) {
    }
}
