package com.arthvritt.platform.adminiam;

import com.arthvritt.platform.command.CommandEvent;
import com.arthvritt.platform.command.CommandGateway;
import com.arthvritt.platform.command.CommandOutcome;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.shared.error.ValidationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BC10 composable RBAC (C18, DL-032). Runs the {@code super_admin}-gated assign/revoke commands
 * through the M4a {@link CommandGateway}; read-only role resolution lives in {@link RoleResolver} (to
 * avoid a gateway↔RBAC cycle). <b>SoD is NOT enforced here</b> — the strict-block / soft-warn-with-
 * deviation checks on a role pair are M4c; until then assignment is un-gated (DL-BE-019 guardrail: do
 * not expose role-assign before M4c).
 */
@Service
public class RbacService {

    private static final Set<String> SUPER_ADMIN_ONLY = Set.of(AdminRole.SUPER_ADMIN.wire());

    private final JdbcTemplate jdbc;
    private final CommandGateway gateway;

    public RbacService(JdbcTemplate jdbc, CommandGateway gateway) {
        this.jdbc = jdbc;
        this.gateway = gateway;
    }

    public CommandResult<Void> assignRole(CommandRequest request, AdminRole role) {
        return gateway.execute(request, SUPER_ADMIN_ONLY, () -> {
            UUID targetId = request.aggregateId();
            requireAdminExists(targetId);
            UUID actorAdminId = actorAdminId(request.actorId());
            // SoD (strict block / soft warn) deliberately NOT checked here — M4c (DL-BE-019).
            // Reactivating a revoked assignment is a clean slate: clear any prior soft-SoD override
            // columns so a stale deviation reference can't linger (M4c re-evaluates SoD on assign).
            jdbc.update("INSERT INTO admin_role_assignment (admin_user_id, role, status, assigned_by) "
                            + "VALUES (?, ?::admin_role, 'active', ?) "
                            + "ON CONFLICT (admin_user_id, role) DO UPDATE SET status = 'active', "
                            + "assigned_at = now(), assigned_by = EXCLUDED.assigned_by, "
                            + "revoked_at = NULL, revoked_by = NULL, "
                            + "sod_warning_acknowledged_at = NULL, override_reason = NULL, "
                            + "deviation_register_entry_id = NULL",
                    targetId, role.wire(), actorAdminId);
            CommandEvent event = new CommandEvent("admin_iam.Role.Assigned", 1,
                    Map.of("admin_user_id", targetId.toString(), "role", role.wire()),
                    Map.of("role", role.wire()),
                    Map.of("role", role.wire(), "status", "active"), true);
            return new CommandOutcome<>(null, event);
        });
    }

    public CommandResult<Void> revokeRole(CommandRequest request, AdminRole role) {
        return gateway.execute(request, SUPER_ADMIN_ONLY, () -> {
            UUID targetId = request.aggregateId();
            UUID actorAdminId = actorAdminId(request.actorId());
            int updated = jdbc.update(
                    "UPDATE admin_role_assignment SET status = 'revoked', revoked_at = now(), revoked_by = ? "
                            + "WHERE admin_user_id = ? AND role = ?::admin_role AND status = 'active'",
                    actorAdminId, targetId, role.wire());
            if (updated == 0) {
                throw new ValidationException("no active assignment of " + role.wire() + " to revoke");
            }
            CommandEvent event = new CommandEvent("admin_iam.Role.Revoked", 1,
                    Map.of("admin_user_id", targetId.toString(), "role", role.wire()),
                    Map.of("role", role.wire(), "status", "active"),
                    Map.of("role", role.wire(), "status", "revoked"), true);
            return new CommandOutcome<>(null, event);
        });
    }

    private void requireAdminExists(UUID adminUserId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM admin_user WHERE admin_user_id = ?",
                Integer.class, adminUserId);
        if (n == null || n == 0) {
            throw new ValidationException("admin user not found: " + adminUserId);
        }
    }

    private UUID actorAdminId(UUID actorIdentityId) {
        UUID id = jdbc.query("SELECT admin_user_id FROM admin_user WHERE identity_id = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, actorIdentityId);
        if (id == null) {
            throw new ValidationException("acting identity is not an admin user: " + actorIdentityId);
        }
        return id;
    }
}
