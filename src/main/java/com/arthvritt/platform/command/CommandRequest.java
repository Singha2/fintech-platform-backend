package com.arthvritt.platform.command;

import com.arthvritt.platform.auth.ActionSensitivity;
import com.arthvritt.platform.auth.AuthSession;

import java.util.UUID;

/**
 * The cross-cutting intent envelope every state-changing command carries into {@link CommandGateway}
 * (M4a). It names <i>who</i> (the resolved {@code session} — its {@code identityId} is the
 * {@code actor_id}, its {@code mfaAssertionId} anchors the #2 freshness gate), <i>what</i> (the
 * {@code aggregateType}/{@code aggregateId} + expected {@code aggregate_version} for P8), and the
 * idempotency key ({@code commandId}, the HTTP {@code X-Command-Id}). The HTTP layer (Walking
 * Skeleton) populates this from headers + the resolved session; M4a tests build it directly.
 *
 * @param session        the acting principal's resolved session (M3b); {@code identityId} = actor_id
 * @param commandId      UUIDv7 idempotency key, unique per logical command per actor (G18, P6)
 * @param context        audit/event context, e.g. {@code admin_iam} (drives the chain shard)
 * @param commandType    machine name of the command, e.g. {@code admin_iam.AdminUser.Disable}
 * @param aggregateType  the target aggregate's type, e.g. {@code AdminUser}
 * @param aggregateId    the target aggregate's id
 * @param expectedVersion the {@code aggregate_version} the caller last read (P8 optimistic lock)
 * @param actorType      the actor kind, e.g. {@code admin_user} (admins require fresh MFA, AU10.3)
 * @param sensitivity    the MFA-freshness band this command demands (B4 §6.4)
 */
public record CommandRequest(
        AuthSession session,
        UUID commandId,
        String context,
        String commandType,
        String aggregateType,
        UUID aggregateId,
        int expectedVersion,
        String actorType,
        ActionSensitivity sensitivity) {

    /** The human account identity that authored the command (actor_id for dedup + audit + C4). */
    public UUID actorId() {
        return session.identityId();
    }
}
