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

import java.util.Map;
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

    private final JdbcTemplate jdbc;
    private final CommandGateway gateway;

    public AdminUserService(JdbcTemplate jdbc, CommandGateway gateway) {
        this.jdbc = jdbc;
        this.gateway = gateway;
    }

    /**
     * Disables the target admin user ({@code active → disabled}). The target id + expected version ride
     * on {@code request}; the gateway enforces idempotency, MFA-freshness, and audit around this body.
     */
    public CommandResult<Void> disableAdminUser(CommandRequest request) {
        return gateway.execute(request, () -> {
            UUID targetId = request.aggregateId();
            AdminRow row = jdbc.query(
                    "SELECT status::text AS status, aggregate_version FROM admin_user WHERE admin_user_id = ?",
                    rs -> rs.next() ? new AdminRow(rs.getString("status"), rs.getInt("aggregate_version")) : null,
                    targetId);
            if (row == null) {
                throw new ValidationException("admin user not found: " + targetId);
            }
            if (row.version() != request.expectedVersion()) {
                throw CommandRejectedException.versionConflict(request.expectedVersion(), row.version());
            }
            if (!"active".equals(row.status())) {
                throw new ValidationException("admin user is not active: " + targetId);
            }

            UUID actorAdminId = jdbc.query(
                    "SELECT admin_user_id FROM admin_user WHERE identity_id = ?",
                    rs -> rs.next() ? rs.getObject(1, UUID.class) : null, request.actorId());
            if (actorAdminId == null) { // the acting identity has no admin_user row (RBAC proper is M4b)
                throw new ValidationException("acting identity is not an admin user: " + request.actorId());
            }
            int updated = jdbc.update(
                    "UPDATE admin_user SET status = 'disabled', disabled_at = now(), disabled_by = ?, "
                            + "aggregate_version = aggregate_version + 1 "
                            + "WHERE admin_user_id = ? AND status = 'active' AND aggregate_version = ?",
                    actorAdminId, targetId, request.expectedVersion());
            if (updated == 0) { // lost the optimistic race between the read above and this write
                Integer current = jdbc.query("SELECT aggregate_version FROM admin_user WHERE admin_user_id = ?",
                        rs -> rs.next() ? rs.getInt(1) : null, targetId);
                throw CommandRejectedException.versionConflict(
                        request.expectedVersion(), current == null ? -1 : current);
            }

            CommandEvent event = new CommandEvent(
                    "admin_iam.AdminUser.Disabled",
                    request.expectedVersion() + 1,
                    Map.of("admin_user_id", targetId.toString()),
                    Map.of("status", "active"),
                    Map.of("status", "disabled"),
                    true);
            return new CommandOutcome<>(null, event);
        });
    }

    private record AdminRow(String status, int version) {
    }
}
