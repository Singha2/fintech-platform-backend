package com.arthvritt.platform.auth;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The IAM-issued claims serialised onto {@code auth_session.tenant_claims} at session establishment
 * (B2 §3.10, G19). These are the C16 tenant-isolation input the repository layer injects as query
 * predicates — they are always <b>server-issued, never trusted from client input</b> (INV-5).
 *
 * <p>M3b owns the serialisation <i>mechanism</i> and these typed factories for the identity kinds live
 * today. The repository-level predicate enforcement is consumed downstream per bounded context (M4+).
 * The admin {@code roles} content is populated by M4 (RBAC/SoD); M3b only provides the container.
 */
public record TenantClaims(Map<String, Object> values) {

    public TenantClaims {
        // Defensive immutable copy; Map.copyOf also rejects null keys/values, which a claim never has.
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    public static TenantClaims of(Map<String, Object> values) {
        return new TenantClaims(values);
    }

    public static TenantClaims empty() {
        return new TenantClaims(Map.of());
    }

    /** Investor session: {@code {investor_id}} (Spec §2.4, C16). */
    public static TenantClaims forInvestor(UUID investorId) {
        return of(Map.of("investor_id", investorId.toString()));
    }

    /** Buyer acknowledgment-user session: {@code {buyer_id}} (DL-021, C16). */
    public static TenantClaims forAckUser(UUID buyerId) {
        return of(Map.of("buyer_id", buyerId.toString()));
    }

    /** Auditor session: {@code {scope_id}} (DL-039, C16). */
    public static TenantClaims forAuditor(UUID scopeId) {
        return of(Map.of("scope_id", scopeId.toString()));
    }

    /** Admin session: {@code {roles}} — the role values themselves are computed by M4 (DL-BE-017). */
    public static TenantClaims forAdmin(List<String> roles) {
        return of(Map.of("roles", List.copyOf(roles)));
    }

    public Object get(String key) {
        return values.get(key);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }
}
