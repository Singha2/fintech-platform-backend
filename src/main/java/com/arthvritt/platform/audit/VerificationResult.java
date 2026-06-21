package com.arthvritt.platform.audit;

import java.util.UUID;

/**
 * Outcome of {@link AuditChainVerifier#verify}. {@code intact} means every row's self-hash recomputed
 * and the per-shard chain links unbroken; otherwise {@code firstBrokenEventId}/{@code reason} locate
 * the first failure.
 */
public record VerificationResult(boolean intact, UUID firstBrokenEventId, String reason) {

    public static VerificationResult ok() {
        return new VerificationResult(true, null, null);
    }

    public static VerificationResult broken(UUID firstBrokenEventId, String reason) {
        return new VerificationResult(false, firstBrokenEventId, reason);
    }
}
