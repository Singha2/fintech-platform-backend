package com.arthvritt.platform.adminiam;

import com.arthvritt.platform.command.CommandEvent;
import com.arthvritt.platform.command.CommandGateway;
import com.arthvritt.platform.command.CommandOutcome;
import com.arthvritt.platform.command.CommandRejectedException;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.shared.error.ValidationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BC10 composable RBAC (C18, DL-032) with SoD enforcement on assignment (C5, DL-033, M4c). Runs the
 * {@code super_admin}-gated assign/revoke commands through the M4a {@link CommandGateway}; read-only
 * role resolution lives in {@link RoleResolver} (to avoid a gateway↔RBAC cycle). The strict-block /
 * soft-warn matrix is evaluated by {@link SodPolicyService} (rules-as-data).
 */
@Service
public class RbacService {

    private static final Set<String> SUPER_ADMIN_ONLY = Set.of(AdminRole.SUPER_ADMIN.wire());

    private final JdbcTemplate jdbc;
    private final CommandGateway gateway;
    private final SodPolicyService sod;
    private final RoleResolver roles;

    public RbacService(JdbcTemplate jdbc, CommandGateway gateway, SodPolicyService sod, RoleResolver roles) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.sod = sod;
        this.roles = roles;
    }

    public CommandResult<Void> assignRole(CommandRequest request, AdminRole role) {
        return assignRole(request, role, null);
    }

    /**
     * Assigns a role with SoD enforcement (C5). A strict pair is blocked ({@code sod_role_block}); a
     * soft pair requires {@code overrideReason} and logs a deviation (RA.2/RA.3). The admin's row is
     * locked {@code FOR UPDATE} first so two concurrent assigns of the two halves of a strict pair
     * can't each miss the other (the check is on absent rows, which row locks alone wouldn't cover).
     */
    public CommandResult<Void> assignRole(CommandRequest request, AdminRole role, String overrideReason) {
        return gateway.execute(request, SUPER_ADMIN_ONLY, () -> {
            UUID targetId = request.aggregateId();
            // Lock the admin row FOR UPDATE (also proves existence) so two concurrent assigns of the two
            // halves of a strict pair can't each miss the other — the strict check is on absent rows.
            UUID locked = jdbc.query("SELECT admin_user_id FROM admin_user WHERE admin_user_id = ? FOR UPDATE",
                    rs -> rs.next() ? rs.getObject(1, UUID.class) : null, targetId);
            if (locked == null) {
                throw new ValidationException("admin user not found: " + targetId);
            }
            UUID actorAdminId = roles.adminUserId(request.actorId());
            List<String> existing = jdbc.queryForList(
                    "SELECT role::text FROM admin_role_assignment WHERE admin_user_id = ? AND status = 'active'",
                    String.class, targetId);
            if (existing.contains(role.wire())) {
                // Already active — idempotent re-assign; skip SoD re-eval so we don't log a duplicate
                // deviation for a standing override.
                CommandEvent unchanged = new CommandEvent("admin_iam.Role.Assigned", 1,
                        Map.of("admin_user_id", targetId.toString(), "role", role.wire(), "already_active", true),
                        Map.of("role", role.wire(), "status", "active"),
                        Map.of("role", role.wire(), "status", "active"), true);
                return new CommandOutcome<>(null, unchanged);
            }

            SodPolicyService.SodEvaluation eval = sod.evaluate(role.wire(), existing);
            UUID deviationId = null;
            switch (eval.decision()) {
                case STRICT_BLOCK -> throw CommandRejectedException.sodRoleBlock(role.wire(), eval.conflictingRole());
                case SOFT_WARN -> {
                    if (overrideReason == null || overrideReason.isBlank()) {
                        throw new ValidationException("role " + role.wire() + " forms a soft SoD pair with "
                                + eval.conflictingRole() + " — an override_reason is required");
                    }
                    deviationId = sod.logDeviation(targetId, request.actorId(), role.wire(),
                            eval.conflictingRole(), overrideReason);
                }
                case CLEAR -> { /* no conflict */ }
            }

            upsertActiveRole(targetId, role, actorAdminId, deviationId, overrideReason);

            Map<String, Object> payload = deviationId == null
                    ? Map.of("admin_user_id", targetId.toString(), "role", role.wire())
                    : Map.of("admin_user_id", targetId.toString(), "role", role.wire(),
                    "sod_warning", true, "override_reason", overrideReason);
            CommandEvent event = new CommandEvent("admin_iam.Role.Assigned", 1, payload,
                    Map.of("role", role.wire()),
                    Map.of("role", role.wire(), "status", "active"), true);
            return new CommandOutcome<>(null, event);
        });
    }

    /**
     * Inserts/reactivates an active assignment. A clean (non-soft) assign nulls any prior soft-SoD
     * override columns; a soft override sets the deviation link + acknowledgement together (the DB
     * {@code admin_role_assignment_soft_sod_override_chk} enforces the trio).
     */
    private void upsertActiveRole(UUID targetId, AdminRole role, UUID actorAdminId,
                                  UUID deviationId, String overrideReason) {
        if (deviationId == null) {
            jdbc.update("INSERT INTO admin_role_assignment (admin_user_id, role, status, assigned_by) "
                            + "VALUES (?, ?::admin_role, 'active', ?) "
                            + "ON CONFLICT (admin_user_id, role) DO UPDATE SET status = 'active', "
                            + "assigned_at = now(), assigned_by = EXCLUDED.assigned_by, "
                            + "revoked_at = NULL, revoked_by = NULL, "
                            + "sod_warning_acknowledged_at = NULL, override_reason = NULL, "
                            + "deviation_register_entry_id = NULL",
                    targetId, role.wire(), actorAdminId);
        } else {
            jdbc.update("INSERT INTO admin_role_assignment (admin_user_id, role, status, assigned_by, "
                            + "deviation_register_entry_id, sod_warning_acknowledged_at, override_reason) "
                            + "VALUES (?, ?::admin_role, 'active', ?, ?, now(), ?) "
                            + "ON CONFLICT (admin_user_id, role) DO UPDATE SET status = 'active', "
                            + "assigned_at = now(), assigned_by = EXCLUDED.assigned_by, "
                            + "revoked_at = NULL, revoked_by = NULL, "
                            + "deviation_register_entry_id = EXCLUDED.deviation_register_entry_id, "
                            + "sod_warning_acknowledged_at = EXCLUDED.sod_warning_acknowledged_at, "
                            + "override_reason = EXCLUDED.override_reason",
                    targetId, role.wire(), actorAdminId, deviationId, overrideReason);
        }
    }

    public CommandResult<Void> revokeRole(CommandRequest request, AdminRole role) {
        return gateway.execute(request, SUPER_ADMIN_ONLY, () -> {
            UUID targetId = request.aggregateId();
            UUID actorAdminId = roles.adminUserId(request.actorId());
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

}
