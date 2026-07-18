package com.arthvritt.platform.buyer.port;

import java.util.Optional;
import java.util.UUID;

/**
 * BC9 Buyer — the in-process port other bounded contexts consult for ack-user-derived facts (bounded-context
 * isolation, ARCH.1 — mirrors {@code investor.InvestorQueryPort}; lives in {@code buyer.port} beside
 * {@link BuyerQueryPort} so a cross-BC reach from {@code listing} is a Java call through the sanctioned port
 * package, not a raw cross-BC dependency). BE-15 (M11-C, DL-BE-090): the shared {@code identity_id -> buyer_id}
 * resolver, reused by {@code SessionController} (session's {@code buyer_id}), {@code BuyerPortalController}
 * (own-scoped reads), and {@code ListingController} (buyer self-ack).
 */
public interface AckUserQueryPort {

    /**
     * {@code identity_id -> buyer_id} for an <b>active</b> {@code buyer_ack_user}. Empty if the identity has
     * no active ack-user row (e.g. an admin, investor, or a deactivated ack user) — callers use that
     * emptiness to mean "not ack-user-scoped, leave un-scoped".
     */
    Optional<UUID> buyerIdForIdentity(UUID identityId);
}
