package com.arthvritt.platform.audit;

import java.util.UUID;

/**
 * The actor sub-shape of an {@link AuditEventEnvelope} (B2 §2.2). Stored as the JSONB {@code actor}
 * column; {@code actorType}, {@code actorId}, {@code sessionId} are always serialized (the DB CHECK
 * requires those keys present — {@code sessionId} may be JSON null for non-user actors).
 *
 * @param actorType      e.g. admin_user, investor, system_scheduler, vendor_escrow (B2 §2.2)
 * @param actorId        user id, system component name, or vendor name
 * @param sessionId      session id; null for non-user actors
 * @param mfaAssertionId MFA assertion anchoring freshness (C7); null when not applicable
 * @param agencyConsentId admin-acting-on-behalf consent ref (DL-013, G5); null otherwise
 */
public record Actor(
        String actorType,
        String actorId,
        String sessionId,
        UUID mfaAssertionId,
        UUID agencyConsentId) {
}
