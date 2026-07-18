package com.arthvritt.platform.investor;

import java.util.Optional;
import java.util.UUID;

/**
 * BC7 Investor — the in-process port other bounded contexts consult for investor-derived facts
 * (bounded-context isolation, ARCH.1 — mirrors {@code document.DocumentPort}: a real interface,
 * implemented by {@link InvestorService}, so a cross-BC reach is a Java call through an abstraction,
 * never a raw cross-BC SQL join). M10-D (BE-17/BE-14): the shared {@code identity_id -> investor_id}
 * resolver (P0), reused by {@code SessionController} (SES-1), {@code InvestorController} (OWN-1),
 * and {@code InvoiceDocumentService} (KYC-1).
 */
public interface InvestorQueryPort {

    /**
     * {@code identity_id -> investor_id} ({@code inv_account.identity_id} is {@code UNIQUE} + indexed,
     * {@code idx_inv_account_identity}). Empty if the identity has no investor account (e.g. an admin
     * or another kind) — callers use that emptiness to mean "not investor-scoped, leave un-scoped".
     */
    Optional<UUID> investorIdForIdentity(UUID identityId);

    /**
     * KYC-1: whether the investor tied to {@code identityId} may download a listing's invoice PDF —
     * {@code inv_account.status ∈ {kyc_approved, mia_signed, active}} <b>or</b> a durable
     * {@code kyc_approved_at}. False (not eligible) if the identity has no investor account at all.
     */
    boolean isKycApprovedForDownload(UUID identityId);
}
