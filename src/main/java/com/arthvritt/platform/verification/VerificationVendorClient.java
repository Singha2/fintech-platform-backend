package com.arthvritt.platform.verification;

import java.util.Map;
import java.util.UUID;

/**
 * The single swappable seam of the Verification ACL: the raw "vendor call". The fixed
 * {@code VerificationService} (cache, persistence, idempotency, audit) calls this; only <i>this</i>
 * bean is replaced (fake → vendor sandbox → production) at the Production gate — the port, aggregate,
 * and events stay put. Phase-1 implementation is {@link StubVerificationVendorClient}.
 */
public interface VerificationVendorClient {

    VendorResponse call(VerificationApi api, UUID subjectId, Map<String, Object> inputs);

    /**
     * @param extractedFields normalised, domain-meaningful fields parsed from the vendor response
     * @param rawPayload      the verbatim vendor response bytes (only its SHA-256 is persisted, V.3)
     */
    record VendorResponse(Map<String, Object> extractedFields, byte[] rawPayload) {
    }
}
