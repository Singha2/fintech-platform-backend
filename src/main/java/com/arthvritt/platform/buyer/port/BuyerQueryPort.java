package com.arthvritt.platform.buyer.port;

import java.util.Optional;
import java.util.UUID;

/**
 * BC9 Buyer read port (M9-A, DL-BE-039). The only cross-context read surface for buyer state — enforced by
 * the ArchUnit ARCH.1 rule. Read-only. Replaces the WS-4 direct read of {@code buyer_account} (DL-BE-034).
 */
public interface BuyerQueryPort {

    /** The buyer's assessed credit limit in paise; rejects (validation) if no limit has been set. */
    long creditLimitPaise(UUID buyerId);

    /** Whether the buyer is currently {@code active} (L.11 go-live re-check). Missing buyer → false. */
    boolean isActive(UUID buyerId);

    /**
     * The auth identity id of the buyer's active acknowledgment user (BA.3), or empty if the buyer has none.
     * The listing BC uses this to address the buyer-ack notification without reading {@code buyer_ack_user}
     * directly (ARCH.1, DL-BE-043).
     */
    Optional<UUID> activeAckUserIdentity(UUID buyerId);
}
