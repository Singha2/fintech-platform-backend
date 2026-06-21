package com.arthvritt.platform.acl;

import com.arthvritt.platform.audit.Actor;
import com.arthvritt.platform.audit.AuditEnvelopes;
import com.arthvritt.platform.audit.AuditLog;

import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

/**
 * Shared substrate for the integration ACLs (M5). Each ACL's fixed service (Verification BC17, Banking
 * BC18, Signing BC19, Notifications BC15) extends this for the two pieces they all repeat: emitting an
 * ACL audit envelope and hashing a verbatim vendor payload. The {@code context}, ACL actor id, and
 * aggregate type are fixed per ACL and supplied at construction.
 *
 * <p>The audit actor is a {@code system} actor because the stub adapters complete in-process; a real
 * adapter's inbound webhook would instead carry {@code actor_type = vendor_aggregator | vendor_escrow |
 * vendor_signing}.
 */
public abstract class AbstractAclService {

    private final AuditLog auditLog;
    private final String context;
    private final String aclActorId;
    private final String aggregateType;

    protected AbstractAclService(AuditLog auditLog, String context, String aclActorId, String aggregateType) {
        this.auditLog = auditLog;
        this.context = context;
        this.aclActorId = aclActorId;
        this.aggregateType = aggregateType;
    }

    /** Appends one ACL event envelope for this aggregate (#5). */
    protected void auditAclEvent(UUID aggregateId, String eventType, Map<String, Object> payload) {
        auditLog.append(AuditEnvelopes.seed(context, aggregateType, aggregateId)
                .eventType(eventType)
                .actor(new Actor("system", aclActorId, null, null, null))
                .payload(payload)
                .build());
    }

    /** SHA-256 of a verbatim vendor payload — only the hash is persisted (the body goes to BC16). */
    protected static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
