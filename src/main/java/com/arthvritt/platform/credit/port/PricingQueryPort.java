package com.arthvritt.platform.credit.port;

import java.util.Optional;
import java.util.UUID;

/**
 * BC3 Credit read port (M9-A, DL-BE-039). The <b>only</b> surface through which another bounded context may
 * read the active pricing band — enforced by the ArchUnit ARCH.1 rule (the {@code listing} BC may depend on
 * {@code ..credit.port..} but nothing else under {@code ..credit..}). Read-only: a query, never a command,
 * so no maker-checker. Replaces the WS-4 documented direct read of {@code risk_pricing_policy} (DL-BE-034).
 */
public interface PricingQueryPort {

    /**
     * The active (non-superseded) band for {@code (buyerId, tenorBucket)}, or empty if none is published.
     * {@code tenorBucket} is the BC3 {@code risk_tenor_bucket} wire value (e.g. {@code "31_60d"}).
     */
    Optional<PricingBand> activeBand(UUID buyerId, String tenorBucket);
}
