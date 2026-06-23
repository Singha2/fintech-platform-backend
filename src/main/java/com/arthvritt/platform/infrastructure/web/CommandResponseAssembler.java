package com.arthvritt.platform.infrastructure.web;

import com.arthvritt.platform.command.CommandResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Builds the B4 §2.3 {@link CommandResponse} from a {@link CommandResult}. The gateway returns only the
 * resulting {@code event_id} (one envelope per command in WS-0), so the rest of the wire body —
 * {@code aggregate_id}, {@code aggregate_version}, {@code event_type}, {@code occurred_at},
 * {@code correlation_id} — is read back from the appended {@code sys_audit_event} row. Sourcing the body
 * from the durable envelope (not from the in-memory handler result) means a replayed command (B4 §2.4)
 * reconstructs exactly the original response, including on a create whose {@code CommandResult.result} is
 * null after replay.
 */
@Component
public class CommandResponseAssembler {

    private final JdbcTemplate jdbc;

    public CommandResponseAssembler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CommandResponse from(CommandResult<?> result) {
        UUID eventId = result.eventId();
        return jdbc.queryForObject(
                "SELECT aggregate_id, aggregate_version, event_type, occurred_at, correlation_id "
                        + "FROM sys_audit_event WHERE event_id = ?",
                (rs, n) -> new CommandResponse(
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getInt("aggregate_version"),
                        List.of(new EmittedEvent(eventId, rs.getString("event_type"),
                                rs.getObject("occurred_at", OffsetDateTime.class).toInstant())),
                        rs.getObject("correlation_id", UUID.class)),
                eventId);
    }
}
