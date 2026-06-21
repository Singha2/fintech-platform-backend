package com.arthvritt.platform.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of {@link AuditLog#append}: the identity plus the fields the audit log assigned —
 * {@code recordedAt}, the {@code chainShard} it landed in, and its place in the hash chain.
 *
 * @param previousEnvelopeHash the predecessor's hash; null for the first row in the shard
 * @param envelopeHash         this envelope's SHA-256 self-hash (32 bytes)
 */
public record AppendedEvent(
        UUID eventId,
        Instant recordedAt,
        String chainShard,
        byte[] previousEnvelopeHash,
        byte[] envelopeHash) {
}
