package com.arthvritt.platform.adminiam;

import com.arthvritt.platform.auth.ActionSensitivity;
import com.arthvritt.platform.auth.AuthSession;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.infrastructure.web.CommandResponse;
import com.arthvritt.platform.infrastructure.web.CommandResponseAssembler;
import com.arthvritt.platform.infrastructure.web.RequestBodies;
import com.arthvritt.platform.shared.error.NotFoundException;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * WS-0 demonstrator of the B4 command surface (DL-BE-030): two BC10 admin commands and one aggregate read,
 * each a thin adapter that builds a {@link CommandRequest} from the request envelope (the resolved session
 * + headers) and dispatches through {@code AdminUserService}/{@code CommandGateway}. The gateway enforces
 * idempotency (#4), MFA-freshness (#2), super_admin authz (#3), and audit (#5); this controller only maps
 * HTTP ↔ command, then renders the B4 §2.3 response from the appended envelope.
 */
@RestController
@RequestMapping("/admin-users")
public class AdminUserController {

    private static final String CONTEXT = "admin_iam";
    private static final String AGGREGATE_TYPE = "AdminUser";

    private final AdminUserService admins;
    private final CommandResponseAssembler responses;
    private final JdbcTemplate jdbc;

    public AdminUserController(AdminUserService admins, CommandResponseAssembler responses, JdbcTemplate jdbc) {
        this.admins = admins;
        this.responses = responses;
        this.jdbc = jdbc;
    }

    @PostMapping("/provision")
    public ResponseEntity<CommandResponse> provision(@AuthenticationPrincipal AuthSession session,
                                                     @RequestHeader("X-Command-Id") UUID commandId,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        String email = RequestBodies.requiredString(body, "email");
        String displayName = RequestBodies.requiredString(body, "display_name");
        String phone = RequestBodies.requiredString(body, "phone");
        // Creating command: the new aggregate's id is derived from (command_id, payload), so a replay of
        // the same command_id+body resolves to the same id (the gateway keys idempotency on the aggregate
        // id), while a divergent body under the same command_id maps to a different id → 409 conflict.
        UUID newAdminId = RequestBodies.deriveAggregateId("admin", commandId, email);
        CommandRequest request = new CommandRequest(session, commandId, CONTEXT, CONTEXT + ".AdminUser.Create",
                AGGREGATE_TYPE, newAdminId, 0, "admin_user", ActionSensitivity.SENSITIVE);

        CommandResult<UUID> result = admins.provisionAdminUser(request, email, displayName, phone);
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(responses.from(result));
    }

    @PostMapping("/{adminUserId}/disable")
    public CommandResponse disable(@AuthenticationPrincipal AuthSession session,
                                   @PathVariable UUID adminUserId,
                                   @RequestHeader("X-Command-Id") UUID commandId,
                                   @RequestHeader("X-Aggregate-Version") int expectedVersion) {
        CommandRequest request = new CommandRequest(session, commandId, CONTEXT, CONTEXT + ".AdminUser.Disable",
                AGGREGATE_TYPE, adminUserId, expectedVersion, "admin_user", ActionSensitivity.SENSITIVE);
        return responses.from(admins.disableAdminUser(request));
    }

    @GetMapping("/{adminUserId}")
    public Map<String, Object> get(@AuthenticationPrincipal AuthSession session,
                                   @PathVariable UUID adminUserId) {
        Map<String, Object> row = jdbc.query(
                "SELECT admin_user_id, status::text AS status, aggregate_version FROM admin_user "
                        + "WHERE admin_user_id = ?",
                rs -> rs.next()
                        ? Map.<String, Object>of(
                                "admin_user_id", rs.getObject("admin_user_id", UUID.class).toString(),
                                "status", rs.getString("status"),
                                "aggregate_version", rs.getInt("aggregate_version"))
                        : null,
                adminUserId);
        if (row == null) {
            throw new NotFoundException("admin user not found: " + adminUserId);
        }
        return row;
    }
}
