package com.arthvritt.platform.command;

import com.arthvritt.platform.audit.Actor;
import com.arthvritt.platform.audit.AppendedEvent;
import com.arthvritt.platform.audit.AuditEnvelopes;
import com.arthvritt.platform.audit.AuditEventEnvelope;
import com.arthvritt.platform.audit.AuditLog;
import com.arthvritt.platform.auth.SessionService;
import com.arthvritt.platform.shared.error.ValidationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * M4a command-control substrate — the single harness every state-changing command routes through.
 * For one command it binds three of the five non-negotiables: idempotency (#4, on
 * {@code sys_command_log}), MFA-freshness enforcement (#2, via {@link SessionService#isMfaFresh}),
 * and the {@code command_id}-stamped audit envelope (#5), plus optimistic concurrency (P8). Hooks for
 * maker-checker (#1) and SoD (#3) land in M4c. See {@code docs/modules/M4a-command-substrate.md}.
 *
 * <p><b>Boundary order</b> (one transaction): idempotency-replay first (a recorded command returns its
 * original envelope without re-checking MFA), then the MFA-fresh gate, then claim → execute → append →
 * record {@code resulting_event_id}. Anything thrown after the claim rolls the whole transaction back,
 * so a rejected command leaves no log row, no mutation, and no envelope (INV-6).
 */
@Service
public class CommandGateway {

    private static final String ADMIN_ACTOR = "admin_user";

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final AuditLog auditLog;
    private final ActorAuthorization authorization;

    public CommandGateway(JdbcTemplate jdbc, SessionService sessions, AuditLog auditLog,
                          ActorAuthorization authorization) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.auditLog = auditLog;
        this.authorization = authorization;
    }

    /** Runs a command with no role requirement (open to any authenticated actor). */
    public <R> CommandResult<R> execute(CommandRequest request, CommandHandler<R> handler) {
        return execute(request, Set.of(), handler);
    }

    /**
     * Runs a command requiring the actor to hold at least one of {@code requiredRoles} (C18). The
     * authz gate sits after the MFA gate and before the claim, so an un-authorised actor is rejected
     * {@code role_not_held} with no log row and no envelope (G22).
     */
    @Transactional
    public <R> CommandResult<R> execute(CommandRequest request, Set<String> requiredRoles,
                                        CommandHandler<R> handler) {
        validate(request);
        boolean adminActor = ADMIN_ACTOR.equals(request.actorType());

        // 0. We need the session to identify the actor (actor_id = session.identityId) and, for admin
        //    actors, to carry an assertion. A session-less or assertion-less admin command is rejected
        //    here — before any dereference of the session (otherwise actorId()/envelope() would NPE).
        if (request.session() == null || (adminActor && request.session().mfaAssertionId() == null)) {
            throw CommandRejectedException.mfaMissing();
        }

        // 1. Idempotency: a command already recorded under this (actor_id, command_id) replays its
        //    original envelope and re-runs no mutation (#4) — without re-checking freshness. A divergent
        //    reuse of the id is a conflict.
        CommandLogRow existing = lookup(request.actorId(), request.commandId());
        if (existing != null) {
            return replayOrConflict(existing, request);
        }

        // 2. MFA-freshness gate for admin actors (#2, AU10.3) — reject before claiming anything.
        if (adminActor && !sessions.isMfaFresh(request.session(), request.sensitivity())) {
            throw CommandRejectedException.mfaExpired();
        }

        // 2b. Authorization gate (M4b, C18) — the actor must hold one of the command's required roles.
        if (!requiredRoles.isEmpty()
                && java.util.Collections.disjoint(authorization.activeRoles(request.actorId()), requiredRoles)) {
            throw CommandRejectedException.roleNotHeld();
        }

        // 3. Claim the (actor_id, command_id) slot. A concurrent first execution that committed between
        //    step 1 and here makes this a no-op insert (0 rows) — fall back to replay/conflict.
        int claimed = jdbc.update(
                "INSERT INTO sys_command_log (actor_id, command_id, command_type, aggregate_type, aggregate_id) "
                        + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (actor_id, command_id) DO NOTHING",
                request.actorId(), request.commandId(), request.commandType(),
                request.aggregateType(), request.aggregateId());
        if (claimed == 0) {
            return replayOrConflict(lookup(request.actorId(), request.commandId()), request);
        }

        // 4. Execute the version-checked mutation. A throw here (version conflict, invariant) rolls back
        //    the claim above — leaving no trace (INV-6).
        CommandOutcome<R> outcome = handler.handle();

        // 5. Append exactly one envelope, in this transaction (#5/X13), stamped with command_id + the
        //    actor's mfa assertion.
        AppendedEvent appended = auditLog.append(envelope(request, outcome.event()));

        // 6. Link the command log row to its resulting envelope, so a later replay can return it.
        jdbc.update("UPDATE sys_command_log SET resulting_event_id = ? WHERE actor_id = ? AND command_id = ?",
                appended.eventId(), request.actorId(), request.commandId());

        return CommandResult.executed(outcome.result(), appended.eventId());
    }

    private <R> CommandResult<R> replayOrConflict(CommandLogRow row, CommandRequest request) {
        if (row == null) {
            // Claimed-then-rolled-back by a concurrent attempt; the slot is momentarily empty.
            throw CommandRejectedException.commandInProgress();
        }
        if (!row.matches(request)) {
            throw CommandRejectedException.idempotencyConflict();
        }
        if (row.resultingEventId() == null) {
            // Claimed but the first execution has not committed its envelope yet.
            throw CommandRejectedException.commandInProgress();
        }
        return CommandResult.replayed(row.resultingEventId());
    }

    /** Fail fast with a typed error before any DB write, so a malformed request never hits a raw
     *  NOT-NULL violation mid-claim. */
    private static void validate(CommandRequest req) {
        require(req.commandId() != null, "commandId is required");
        require(req.aggregateId() != null, "aggregateId is required");
        require(notBlank(req.aggregateType()), "aggregateType is required");
        require(notBlank(req.commandType()), "commandType is required");
        require(notBlank(req.context()), "context is required");
        require(notBlank(req.actorType()), "actorType is required");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ValidationException("invalid command request: " + message);
        }
    }

    private CommandLogRow lookup(UUID actorId, UUID commandId) {
        return jdbc.query(
                "SELECT command_type, aggregate_type, aggregate_id, resulting_event_id "
                        + "FROM sys_command_log WHERE actor_id = ? AND command_id = ?",
                rs -> rs.next()
                        ? new CommandLogRow(rs.getString("command_type"), rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID.class), rs.getObject("resulting_event_id", UUID.class))
                        : null,
                actorId, commandId);
    }

    private AuditEventEnvelope envelope(CommandRequest req, CommandEvent e) {
        return AuditEnvelopes.seed(req.context(), req.aggregateType(), req.aggregateId())
                .eventType(e.eventType())
                .actor(new Actor(req.actorType(), req.actorId().toString(),
                        req.session().sessionId().toString(), req.session().mfaAssertionId(), null))
                .aggregateVersion(e.aggregateVersion()) // override the seed default of 1
                .commandId(req.commandId())
                .payload(e.payload())
                .beforeState(e.beforeState())
                .afterState(e.afterState())
                .stateTransition(e.stateTransition())
                .build();
    }

    private record CommandLogRow(String commandType, String aggregateType, UUID aggregateId,
                                 UUID resultingEventId) {
        boolean matches(CommandRequest req) {
            return commandType.equals(req.commandType())
                    && aggregateType.equals(req.aggregateType())
                    && aggregateId.equals(req.aggregateId());
        }
    }
}
