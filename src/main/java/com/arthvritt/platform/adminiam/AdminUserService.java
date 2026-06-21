package com.arthvritt.platform.adminiam;

import com.arthvritt.platform.command.CommandEvent;
import com.arthvritt.platform.command.CommandGateway;
import com.arthvritt.platform.command.CommandOutcome;
import com.arthvritt.platform.command.CommandRejectedException;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.audit.Actor;
import com.arthvritt.platform.audit.AuditEnvelopes;
import com.arthvritt.platform.audit.AuditLog;
import com.arthvritt.platform.auth.SessionService;
import com.arthvritt.platform.shared.Ids;
import com.arthvritt.platform.shared.error.ValidationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BC10 Admin IAM — the admin-user lifecycle commands, all issued through the {@link CommandGateway}
 * (so each inherits idempotency, MFA-freshness, super_admin authz, and audit). Provisioning, the
 * {@code invited→active} MFA gate (AU10.1), and disable/enable with the identity+session cascade are
 * M4b/M4d; the four-eyes disable (propose → approve via {@link MakerCheckerGate}) is the M4d proving
 * flow (C4). There is no HTTP surface yet. RBAC is {@link RbacService}; SoD is {@link SodPolicyService}.
 */
@Service
public class AdminUserService {

    private static final Set<String> SUPER_ADMIN_ONLY = Set.of(AdminRole.SUPER_ADMIN.wire());
    private static final String DISABLE_PROPOSED = "admin_iam.AdminUser.DisableProposed";

    private final JdbcTemplate jdbc;
    private final CommandGateway gateway;
    private final SessionService sessions;
    private final RoleResolver roles;
    private final MakerCheckerGate makerChecker;
    private final AuditLog auditLog;

    public AdminUserService(JdbcTemplate jdbc, CommandGateway gateway, SessionService sessions,
                            RoleResolver roles, MakerCheckerGate makerChecker, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.sessions = sessions;
        this.roles = roles;
        this.makerChecker = makerChecker;
        this.auditLog = auditLog;
    }

    /**
     * Maker side of the four-eyes disable (M4d): records the intent as a {@code DisableProposed}
     * envelope. No state change — the admin stays active until a <i>different</i> super_admin approves.
     * Only one open proposal is allowed per admin (the row is locked so concurrent proposes serialise),
     * so the maker-of-record is unambiguous.
     */
    public CommandResult<Void> proposeDisableAdmin(CommandRequest request) {
        return gateway.execute(request, SUPER_ADMIN_ONLY, () -> {
            UUID targetId = request.aggregateId();
            lockAdmin(targetId);
            AdminRow row = loadFor(targetId, "active"); // can only propose disabling an active admin
            if (makerChecker.hasOpenProposal("AdminUser", targetId, DISABLE_PROPOSED)) {
                throw new ValidationException("a disable proposal is already pending for admin: " + targetId);
            }
            CommandEvent proposal = new CommandEvent(DISABLE_PROPOSED, row.version(),
                    Map.of("admin_user_id", targetId.toString(), "action", "disable"),
                    null, null, false); // a proposal records intent; it is not a state transition
            return new CommandOutcome<>(null, proposal);
        });
    }

    /**
     * Checker side (M4d, C4): a different super_admin approves the open proposal. The
     * {@link MakerCheckerGate} compares the proposal's maker to the checker's {@code actor_id} — equal ⇒
     * {@code BLOCKED} (a committed {@code MakerChecker.Blocked} envelope, no transition); distinct ⇒
     * apply the disable cascade (anchored to the <i>proposed</i> version, so a state drift since the
     * proposal conflicts) and emit {@code MakerChecker.Approved} alongside the transition.
     */
    public CommandResult<MakerCheckerOutcome> approveDisableAdmin(CommandRequest request) {
        return gateway.execute(request, SUPER_ADMIN_ONLY, () -> {
            UUID targetId = request.aggregateId();
            UUID checkerActorId = request.actorId();
            MakerCheckerGate.MakerCheckerDecision decision = makerChecker.evaluate(
                    "AdminUser", targetId, DISABLE_PROPOSED, checkerActorId);
            if (!decision.hasProposal()) {
                throw new ValidationException("no open disable proposal to approve for admin: " + targetId);
            }
            if (decision.blocked()) {
                // Committed reject (X11/G22): emit MakerChecker.Blocked as the command's event, no transition.
                CommandEvent blocked = new CommandEvent(MakerCheckerGate.BLOCKED_EVENT, decision.proposedVersion(),
                        Map.of("record_id", targetId.toString(), "admin_user_id", targetId.toString(),
                                "action", "disable"),
                        null, null, false);
                return new CommandOutcome<>(MakerCheckerOutcome.BLOCKED, blocked);
            }
            // Approved: apply the transition (guarded on the proposed version), then record
            // MakerChecker.Approved beside it (one transaction).
            AdminRow row = loadFor(targetId, "active");
            UUID checkerAdminId = roles.adminUserId(checkerActorId);
            int sessionsRevoked = applyDisableTransition(targetId, row.identityId(), checkerAdminId,
                    decision.proposedVersion());
            auditLog.append(AuditEnvelopes.seed("admin_iam", "AdminUser", targetId)
                    .eventType(MakerCheckerGate.APPROVED_EVENT)
                    .aggregateVersion(decision.proposedVersion() + 1)
                    .actor(new Actor("admin_user", checkerActorId.toString(), null, null, null))
                    .payload(Map.of("record_id", targetId.toString(),
                            "maker_user_id", decision.makerActorId().toString(),
                            "checker_user_id", checkerActorId.toString(), "decision", "approved"))
                    .build());
            return new CommandOutcome<>(MakerCheckerOutcome.APPROVED,
                    disabledEvent(targetId, decision.proposedVersion() + 1, sessionsRevoked));
        });
    }

    /** Provisions a new admin (its {@code auth_identity} + an {@code invited} {@code admin_user} row). */
    public CommandResult<UUID> provisionAdminUser(CommandRequest request, String email,
                                                  String displayName, String phoneE164) {
        return gateway.execute(request, SUPER_ADMIN_ONLY, () -> {
            if (emailTaken(email)) {
                throw new ValidationException("admin email already in use: " + email);
            }
            // The caller mints the new admin's id (it is request.aggregateId), so the gateway's
            // idempotency key + envelope name the entity being created.
            UUID adminUserId = request.aggregateId();
            UUID identityId = Ids.newId();
            try {
                jdbc.update("INSERT INTO auth_identity (identity_id, kind, email, phone_e164, display_name, status) "
                                + "VALUES (?, 'admin_user'::identity_kind_enum, ?, ?, ?, 'active'::identity_status_enum)",
                        identityId, email, phoneE164, displayName);
                jdbc.update("INSERT INTO admin_user (admin_user_id, identity_id, email, display_name, status) "
                                + "VALUES (?, ?, ?, ?, 'invited')",
                        adminUserId, identityId, email, displayName);
            } catch (DuplicateKeyException e) {
                // The pre-check above lost a race, or the caller reused an existing admin id.
                throw new ValidationException("admin email or id already in use: " + email);
            }
            CommandEvent event = new CommandEvent("admin_iam.AdminUser.Created", 1,
                    Map.of("admin_user_id", adminUserId.toString(), "email", email),
                    Map.of(), Map.of("status", "invited"), true);
            return new CommandOutcome<>(adminUserId, event);
        });
    }

    /** Activates an invited admin ({@code invited → active}) — gated on a confirmed TOTP (AU10.1, C7). */
    public CommandResult<Void> activateAdminUser(CommandRequest request) {
        return gateway.execute(request, SUPER_ADMIN_ONLY, () -> {
            UUID targetId = request.aggregateId();
            loadFor(targetId, "invited");
            requireConfirmedTotp(targetId);
            applyStatus(targetId, "active", request.expectedVersion(), "invited");
            return statusTransition(targetId, request.expectedVersion() + 1,
                    "admin_iam.AdminUser.Activated", "invited", "active");
        });
    }

    /** Re-enables a disabled admin ({@code disabled → active}) — also AU10.1-gated. */
    public CommandResult<Void> enableAdminUser(CommandRequest request) {
        return gateway.execute(request, SUPER_ADMIN_ONLY, () -> {
            UUID targetId = request.aggregateId();
            AdminRow row = loadFor(targetId, "disabled");
            requireConfirmedTotp(targetId);
            int updated = jdbc.update(
                    "UPDATE admin_user SET status = 'active', disabled_at = NULL, disabled_by = NULL, "
                            + "aggregate_version = aggregate_version + 1 "
                            + "WHERE admin_user_id = ? AND status = 'disabled' AND aggregate_version = ?",
                    targetId, request.expectedVersion());
            if (updated == 0) {
                throw versionConflict(targetId, request.expectedVersion());
            }
            // Reverse the disable cascade's identity lock so the admin can authenticate again (their
            // old sessions stay revoked — they get a fresh one on next login).
            jdbc.update("UPDATE auth_identity SET status = 'active' WHERE identity_id = ?", row.identityId());
            return statusTransition(targetId, request.expectedVersion() + 1,
                    "admin_iam.AdminUser.Enabled", "disabled", "active");
        });
    }

    /**
     * Disables the target admin user ({@code active → disabled}). Requires the actor to hold
     * {@code super_admin} (user management — M4b closes the M4a authz gap). The gateway enforces
     * idempotency, MFA-freshness, authz, and audit around this body.
     */
    public CommandResult<Void> disableAdminUser(CommandRequest request) {
        return gateway.execute(request, SUPER_ADMIN_ONLY, () -> {
            UUID targetId = request.aggregateId();
            AdminRow row = loadFor(targetId, "active");
            UUID actorAdminId = roles.adminUserId(request.actorId());
            // Direct command: honour the caller-supplied optimistic version (P8). (The four-eyes approve
            // path instead uses the freshly-read version — the checker approves the current state.)
            int sessionsRevoked = applyDisableTransition(targetId, row.identityId(), actorAdminId,
                    request.expectedVersion());
            return new CommandOutcome<>(null, disabledEvent(targetId, request.expectedVersion() + 1, sessionsRevoked));
        });
    }

    // --- shared command-handler helpers --------------------------------------------------------

    /** The disable state transition + cascade, shared by the direct (M4b) and four-eyes (M4d) paths. */
    private int applyDisableTransition(UUID targetId, UUID identityId, UUID actorAdminId, int expectedVersion) {
        int updated = jdbc.update(
                "UPDATE admin_user SET status = 'disabled', disabled_at = now(), disabled_by = ?, "
                        + "aggregate_version = aggregate_version + 1 "
                        + "WHERE admin_user_id = ? AND status = 'active' AND aggregate_version = ?",
                actorAdminId, targetId, expectedVersion);
        if (updated == 0) { // lost the optimistic race between loadFor and this write
            throw versionConflict(targetId, expectedVersion);
        }
        // Cascade (defense beyond the RoleResolver authz filter): block re-authentication by disabling
        // the auth identity, and kill any live sessions so existing ones die at once.
        jdbc.update("UPDATE auth_identity SET status = 'disabled' WHERE identity_id = ?", identityId);
        return sessions.revokeAllForIdentity(identityId);
    }

    private CommandEvent disabledEvent(UUID targetId, int newVersion, int sessionsRevoked) {
        return new CommandEvent("admin_iam.AdminUser.Disabled", newVersion,
                Map.of("admin_user_id", targetId.toString(), "sessions_revoked", sessionsRevoked),
                Map.of("status", "active"), Map.of("status", "disabled"), true);
    }

    /** Locks the admin row so concurrent proposals for one admin serialise (the one-open-proposal guard). */
    private void lockAdmin(UUID adminUserId) {
        UUID locked = jdbc.query("SELECT admin_user_id FROM admin_user WHERE admin_user_id = ? FOR UPDATE",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, adminUserId);
        if (locked == null) {
            throw new ValidationException("admin user not found: " + adminUserId);
        }
    }

    private boolean emailTaken(String email) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM admin_user WHERE email = ?", Integer.class, email);
        return n != null && n > 0;
    }

    private AdminRow loadFor(UUID adminUserId, String expectedStatus) {
        AdminRow row = jdbc.query(
                "SELECT status::text AS status, aggregate_version, identity_id FROM admin_user WHERE admin_user_id = ?",
                rs -> rs.next() ? new AdminRow(rs.getString("status"), rs.getInt("aggregate_version"),
                        rs.getObject("identity_id", UUID.class)) : null,
                adminUserId);
        if (row == null) {
            throw new ValidationException("admin user not found: " + adminUserId);
        }
        if (!expectedStatus.equals(row.status())) {
            throw new ValidationException("admin user is not " + expectedStatus + ": " + adminUserId);
        }
        return row;
    }

    /** AU10.1: at least one active, confirmed (verified-once) TOTP factor for this admin (C7). */
    private void requireConfirmedTotp(UUID adminUserId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM auth_mfa_factor f JOIN admin_user au ON au.identity_id = f.identity_id "
                        + "WHERE au.admin_user_id = ? AND f.kind = 'totp' "
                        + "AND f.revoked_at IS NULL AND f.last_used_at IS NOT NULL",
                Integer.class, adminUserId);
        if (n == null || n == 0) {
            throw new ValidationException("activation requires a confirmed TOTP factor (AU10.1)");
        }
    }

    private void applyStatus(UUID adminUserId, String to, int expectedVersion, String from) {
        int updated = jdbc.update(
                "UPDATE admin_user SET status = ?::admin_user_status, aggregate_version = aggregate_version + 1 "
                        + "WHERE admin_user_id = ? AND status = ?::admin_user_status AND aggregate_version = ?",
                to, adminUserId, from, expectedVersion);
        if (updated == 0) {
            throw versionConflict(adminUserId, expectedVersion);
        }
    }

    private CommandOutcome<Void> statusTransition(UUID adminUserId, int newVersion, String eventType,
                                                  String from, String to) {
        CommandEvent event = new CommandEvent(eventType, newVersion,
                Map.of("admin_user_id", adminUserId.toString()),
                Map.of("status", from), Map.of("status", to), true);
        return new CommandOutcome<>(null, event);
    }

    private CommandRejectedException versionConflict(UUID adminUserId, int expectedVersion) {
        Integer current = jdbc.query("SELECT aggregate_version FROM admin_user WHERE admin_user_id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, adminUserId);
        return CommandRejectedException.versionConflict(expectedVersion, current == null ? -1 : current);
    }

    private record AdminRow(String status, int version, UUID identityId) {
    }
}
