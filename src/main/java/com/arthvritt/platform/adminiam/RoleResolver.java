package com.arthvritt.platform.adminiam;

import com.arthvritt.platform.command.ActorAuthorization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only RBAC resolution (C18) — the {@link ActorAuthorization} the {@link
 * com.arthvritt.platform.command.CommandGateway} authz gate consults. Kept separate from
 * {@link RbacService} (which issues assign/revoke <i>through</i> the gateway) so there is no
 * gateway↔RBAC constructor cycle: the gateway depends only on this read-only resolver.
 */
@Service
public class RoleResolver implements ActorAuthorization {

    private final JdbcTemplate jdbc;

    public RoleResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The union of the actor's active role assignments — empty if they are not an <i>active</i> admin
     * (C18). The {@code admin_user.status = 'active'} filter is load-bearing: disabling an admin must
     * deauthorize them even though their role rows are not individually revoked.
     */
    @Override
    public Set<String> activeRoles(UUID actorIdentityId) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT ra.role::text FROM admin_role_assignment ra "
                        + "JOIN admin_user au ON au.admin_user_id = ra.admin_user_id "
                        + "WHERE au.identity_id = ? AND ra.status = 'active' AND au.status = 'active'",
                String.class, actorIdentityId));
    }
}
