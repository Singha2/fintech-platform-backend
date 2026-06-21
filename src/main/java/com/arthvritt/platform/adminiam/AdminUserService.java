package com.arthvritt.platform.adminiam;

import com.arthvritt.platform.command.CommandEvent;
import com.arthvritt.platform.command.CommandGateway;
import com.arthvritt.platform.command.CommandOutcome;
import com.arthvritt.platform.command.CommandRejectedException;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.shared.Ids;
import com.arthvritt.platform.shared.error.ValidationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BC10 Admin IAM — the surface that issues admin commands through the {@link CommandGateway}. In M4a
 * this exists to <b>prove the substrate</b> with the thinnest real command ({@link #disableAdminUser}).
 * The admin lifecycle proper (provisioning, the {@code invited→active} MFA gate), RBAC role-authz, and
 * TOTP enrollment are M4b; SoD + maker-checker are M4c. There is no HTTP surface yet, so the
 * not-yet-role-authorized path is not externally reachable (DL-BE-018).
 */
@Service
public class AdminUserService {

    private static final Set<String> SUPER_ADMIN_ONLY = Set.of(AdminRole.SUPER_ADMIN.wire());

    private final JdbcTemplate jdbc;
    private final CommandGateway gateway;

    public AdminUserService(JdbcTemplate jdbc, CommandGateway gateway) {
        this.jdbc = jdbc;
        this.gateway = gateway;
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
            loadFor(targetId, "disabled");
            requireConfirmedTotp(targetId);
            int updated = jdbc.update(
                    "UPDATE admin_user SET status = 'active', disabled_at = NULL, disabled_by = NULL, "
                            + "aggregate_version = aggregate_version + 1 "
                            + "WHERE admin_user_id = ? AND status = 'disabled' AND aggregate_version = ?",
                    targetId, request.expectedVersion());
            if (updated == 0) {
                throw versionConflict(targetId, request.expectedVersion());
            }
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
            loadFor(targetId, "active");
            UUID actorAdminId = actorAdminId(request.actorId());
            int updated = jdbc.update(
                    "UPDATE admin_user SET status = 'disabled', disabled_at = now(), disabled_by = ?, "
                            + "aggregate_version = aggregate_version + 1 "
                            + "WHERE admin_user_id = ? AND status = 'active' AND aggregate_version = ?",
                    actorAdminId, targetId, request.expectedVersion());
            if (updated == 0) { // lost the optimistic race between loadFor and this write
                throw versionConflict(targetId, request.expectedVersion());
            }
            return statusTransition(targetId, request.expectedVersion() + 1,
                    "admin_iam.AdminUser.Disabled", "active", "disabled");
        });
    }

    // --- shared command-handler helpers --------------------------------------------------------

    private boolean emailTaken(String email) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM admin_user WHERE email = ?", Integer.class, email);
        return n != null && n > 0;
    }

    private AdminRow loadFor(UUID adminUserId, String expectedStatus) {
        AdminRow row = jdbc.query(
                "SELECT status::text AS status, aggregate_version FROM admin_user WHERE admin_user_id = ?",
                rs -> rs.next() ? new AdminRow(rs.getString("status"), rs.getInt("aggregate_version")) : null,
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

    private UUID actorAdminId(UUID actorIdentityId) {
        UUID id = jdbc.query("SELECT admin_user_id FROM admin_user WHERE identity_id = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, actorIdentityId);
        if (id == null) { // unreachable once authz requires a role (roles FK admin_user); kept defensive
            throw new ValidationException("acting identity is not an admin user: " + actorIdentityId);
        }
        return id;
    }

    private record AdminRow(String status, int version) {
    }
}
