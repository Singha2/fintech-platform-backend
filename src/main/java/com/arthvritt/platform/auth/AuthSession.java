package com.arthvritt.platform.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * A resolved, server-side view of an {@code auth_session} row. The session cookie carries only
 * {@code sessionId}; every other field here is loaded from the row, never trusted from the client
 * (INV-3). {@code mfaAssertionId} anchors {@link SessionService#isMfaFresh}; {@code tenantClaims} is
 * the C16 isolation input.
 */
public record AuthSession(
        UUID sessionId,
        UUID identityId,
        String status,
        Instant issuedAt,
        Instant lastSeenAt,
        Instant idleExpiresAt,
        Instant absoluteExpiresAt,
        UUID mfaAssertionId,
        TenantClaims tenantClaims) {
}
