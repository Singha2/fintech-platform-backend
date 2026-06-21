package com.arthvritt.platform.verification;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The outcome of a verification. {@code verificationId} is the ACL idempotency key (= the vendor
 * {@code client_request_id}); on a cache hit it is the <i>original</i> request's id. {@code ttlUntil}
 * is null for one-shot APIs.
 */
public record VerificationResult(UUID verificationId, VerificationStatus status,
                                 Map<String, Object> extractedFields, Instant ttlUntil) {
}
