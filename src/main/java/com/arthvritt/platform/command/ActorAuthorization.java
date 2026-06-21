package com.arthvritt.platform.command;

import java.util.Set;
import java.util.UUID;

/**
 * The authorization port the {@link CommandGateway} consults to enforce role-based command authz
 * (C18) without depending on BC10's tables directly. Implemented by the admin-IAM RBAC service (M4b).
 * Returns the actor's <i>effective</i> active roles (the union of their active role assignments);
 * the gateway rejects a command whose required roles don't intersect this set.
 */
public interface ActorAuthorization {

    Set<String> activeRoles(UUID actorIdentityId);
}
