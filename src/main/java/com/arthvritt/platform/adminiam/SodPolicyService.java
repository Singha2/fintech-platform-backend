package com.arthvritt.platform.adminiam;

import com.arthvritt.platform.audit.Actor;
import com.arthvritt.platform.audit.AuditEnvelopes;
import com.arthvritt.platform.audit.AuditLog;
import com.arthvritt.platform.command.CommandEvent;
import com.arthvritt.platform.command.CommandGateway;
import com.arthvritt.platform.command.CommandOutcome;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.shared.Ids;
import com.arthvritt.platform.shared.error.ValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BC10 Segregation-of-Duties engine (C5, DL-033). The strict/soft role pairs are <b>rules-as-data</b>
 * in {@code admin_sod_policy} (one active row), so the matrix changes via {@link #publishSodPolicy}
 * rather than code. Provides the evaluation {@link RbacService#assignRole} gates on, the
 * deviation-register writer for soft overrides, and the {@code super_admin}-gated publish/review
 * commands. Strict blocks are pre-authorisation rejects (no envelope, G22); soft overrides and reviews
 * are audited.
 */
@Service
public class SodPolicyService {

    /** Phase-1 fixed policy (SP.2, C5/DL-033). */
    private static final List<List<String>> STRICT_DEFAULT =
            List.of(List.of("credit_reviewer", "treasury_and_settlement"));
    private static final List<List<String>> SOFT_DEFAULT = List.of(
            List.of("super_admin", "compliance_reviewer"),
            List.of("ops_executive", "treasury_and_settlement"),
            List.of("credit_reviewer", "compliance_reviewer"));
    private static final Set<String> SUPER_ADMIN_ONLY = Set.of(AdminRole.SUPER_ADMIN.wire());

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CommandGateway gateway;
    private final AuditLog auditLog;
    private final RoleResolver roles;

    public SodPolicyService(JdbcTemplate jdbc, ObjectMapper mapper, CommandGateway gateway,
                            AuditLog auditLog, RoleResolver roles) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.gateway = gateway;
        this.auditLog = auditLog;
        this.roles = roles;
    }

    /** Seeds the Phase-1 fixed policy (bootstrap / dev). Production publishes via {@link #publishSodPolicy}. */
    public void seedDefaultPolicy(UUID publishedByAdminUserId) {
        insertPolicy(Ids.newId(), STRICT_DEFAULT, SOFT_DEFAULT, publishedByAdminUserId);
    }

    /**
     * Evaluates adding {@code roleToAdd} against the admin's {@code existingRoles} under the current
     * policy: strict pairs win over soft. With no active policy, nothing is constrained (CLEAR) — the
     * policy must be seeded in any real environment.
     */
    public SodEvaluation evaluate(String roleToAdd, List<String> existingRoles) {
        Policy policy = currentPolicy();
        if (policy == null) {
            // Fail closed: a regulated SoD control must not silently permit strict pairs because the
            // policy is missing. An explicitly-published *empty* policy is different (it returns CLEAR).
            throw new ValidationException("no active SoD policy — role assignment refused");
        }
        for (String existing : existingRoles) {
            if (!existing.equals(roleToAdd) && policy.strict().contains(pair(roleToAdd, existing))) {
                return new SodEvaluation(SodDecision.STRICT_BLOCK, existing);
            }
        }
        for (String existing : existingRoles) {
            if (!existing.equals(roleToAdd) && policy.soft().contains(pair(roleToAdd, existing))) {
                return new SodEvaluation(SodDecision.SOFT_WARN, existing);
            }
        }
        return new SodEvaluation(SodDecision.CLEAR, null);
    }

    /**
     * Records a soft-SoD override in the managed deviation register (RA.3) and emits
     * {@code SodSoftDeviation.Logged}. Returns the new entry id, which the caller links onto the
     * assignment. Runs in the caller's (assignRole) transaction.
     */
    public UUID logDeviation(UUID adminUserId, UUID actorIdentityId, String roleA, String roleB, String reason) {
        UUID deviationId = Ids.newId();
        jdbc.update("INSERT INTO admin_deviation_log (deviation_register_entry_id, admin_user_id, combo, reason) "
                + "VALUES (?, ?, ARRAY[?, ?], ?)", deviationId, adminUserId, roleA, roleB, reason);
        auditLog.append(AuditEnvelopes.seed("admin_iam", "DeviationEntry", deviationId)
                .eventType("admin_iam.SodSoftDeviation.Logged")
                .actor(new Actor("admin_user", actorIdentityId.toString(), null, null, null))
                .payload(Map.of("admin_user_id", adminUserId.toString(), "combo", List.of(roleA, roleB),
                        "reason", reason, "deviation_register_entry_id", deviationId.toString()))
                .build());
        return deviationId;
    }

    /** Supersedes the current policy with a new one (SP.1). The caller mints the policy id (aggregateId). */
    public CommandResult<Void> publishSodPolicy(CommandRequest request,
                                                List<List<String>> strictPairs, List<List<String>> softPairs) {
        return gateway.execute(request, SUPER_ADMIN_ONLY, () -> {
            // Validate before persisting: a malformed pair (wrong arity, unknown/duplicate role) would
            // never match a real Set.of(a,b) at evaluate() time and would silently disable the control.
            validatePairs(strictPairs);
            validatePairs(softPairs);
            UUID policyId = request.aggregateId();
            try {
                insertPolicy(policyId, strictPairs, softPairs, roles.adminUserId(request.actorId()));
            } catch (DuplicateKeyException e) {
                // Concurrent publisher won the one-active slot, or the policy id was reused.
                throw new ValidationException("policy id in use or a concurrent publish won — retry with a new id");
            }
            CommandEvent event = new CommandEvent("admin_iam.SodPolicy.Published", 1,
                    Map.of("sod_policy_id", policyId.toString()), Map.of(), Map.of("active", true), true);
            return new CommandOutcome<>(null, event);
        });
    }

    private static void validatePairs(List<List<String>> pairs) {
        for (List<String> pair : pairs) {
            if (pair.size() != 2) {
                throw new ValidationException("each SoD pair must list exactly two roles: " + pair);
            }
            AdminRole.fromWire(pair.get(0)); // rejects unknown role wires
            AdminRole.fromWire(pair.get(1));
            if (pair.get(0).equals(pair.get(1))) {
                throw new ValidationException("a SoD pair's two roles must be distinct: " + pair);
            }
        }
    }

    /** Quarterly review of a deviation entry — sets the review fields exactly once (DE.1/DE.2). */
    public CommandResult<Void> reviewDeviation(CommandRequest request, String decision) {
        return gateway.execute(request, SUPER_ADMIN_ONLY, () -> {
            if (decision == null || decision.isBlank()) {
                throw new ValidationException("a review decision is required");
            }
            UUID deviationId = request.aggregateId();
            int updated = jdbc.update("UPDATE admin_deviation_log SET quarterly_review_status = 'reviewed', "
                            + "review_decision = ?, reviewed_at = now(), reviewed_by = ? "
                            + "WHERE deviation_register_entry_id = ? AND quarterly_review_status = 'pending'",
                    decision, roles.adminUserId(request.actorId()), deviationId);
            if (updated == 0) {
                throw new ValidationException("no pending deviation to review: " + deviationId);
            }
            CommandEvent event = new CommandEvent("admin_iam.DeviationRegister.EntryReviewed", 1,
                    Map.of("deviation_register_entry_id", deviationId.toString(), "decision", decision),
                    Map.of("quarterly_review_status", "pending"),
                    Map.of("quarterly_review_status", "reviewed"), true);
            return new CommandOutcome<>(null, event);
        });
    }

    // --- internals -----------------------------------------------------------------------------

    /** The current active policy, or {@code null} if none is seeded (callers fail closed). */
    private Policy currentPolicy() {
        return jdbc.query(
                "SELECT strict_pairs::text AS strict, soft_pairs::text AS soft "
                        + "FROM admin_sod_policy WHERE superseded_by IS NULL",
                rs -> rs.next() ? new Policy(parsePairs(rs.getString("strict")), parsePairs(rs.getString("soft"))) : null);
    }

    /**
     * Inserts a new active policy, superseding any current one. To respect the one-active unique index
     * AND the self-referential FK, the new row is first written superseded-by-self (inactive), the old
     * active row is pointed at it, then the new row is activated — all within the command transaction.
     */
    private void insertPolicy(UUID policyId, List<List<String>> strict, List<List<String>> soft, UUID publishedBy) {
        // enforcement_tier is a required scalar but is descriptive-only in Phase 1: the strict-vs-soft
        // decision comes entirely from which pair-set a role lands in (strict_pairs / soft_pairs), so
        // the evaluator never reads this column. Kept constant; revisit if a policy-wide tier is needed.
        jdbc.update("INSERT INTO admin_sod_policy "
                        + "(sod_policy_id, strict_pairs, soft_pairs, enforcement_tier, published_by, superseded_by) "
                        + "VALUES (?, ?::jsonb, ?::jsonb, 'soft_warn_with_log'::admin_sod_enforcement_tier, ?, ?)",
                policyId, toJson(strict), toJson(soft), publishedBy, policyId);
        jdbc.update("UPDATE admin_sod_policy SET superseded_by = ? WHERE superseded_by IS NULL AND sod_policy_id <> ?",
                policyId, policyId);
        jdbc.update("UPDATE admin_sod_policy SET superseded_by = NULL WHERE sod_policy_id = ?", policyId);
    }

    private static Set<String> pair(String a, String b) {
        return Set.of(a, b);
    }

    private Set<Set<String>> parsePairs(String json) {
        try {
            List<List<String>> raw = mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
            Set<Set<String>> pairs = new HashSet<>();
            for (List<String> p : raw) {
                pairs.add(new HashSet<>(p));
            }
            return pairs;
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse SoD policy pairs", e);
        }
    }

    private String toJson(List<List<String>> pairs) {
        try {
            return mapper.writeValueAsString(pairs);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialise SoD policy pairs", e);
        }
    }

    public record SodEvaluation(SodDecision decision, String conflictingRole) {
    }

    private record Policy(Set<Set<String>> strict, Set<Set<String>> soft) {
    }
}
