package com.arthvritt.platform.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * The proof that an identity passed MFA: the {@code assertion_id} minted when an OTP challenge is
 * consumed (non-negotiable #2 anchor). M3b's {@code isMfaFresh} and sessions consume this.
 */
public record MfaAssertion(UUID assertionId, UUID identityId, Instant mintedAt) {
}
