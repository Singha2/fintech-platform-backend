package com.arthvritt.platform.verification;

import com.arthvritt.platform.shared.error.ValidationException;

import java.time.Duration;

/**
 * The aggregator vendor APIs the Verification ACL (BC17) exposes as domain operations (DL-026,
 * `verification_api_enum`). Each carries its result TTL (A2 §1.4) — how long a completed result is
 * cache-reusable before it must be re-fetched. One-shot point-in-time checks (IRN, e-way bill, AML/PEP)
 * have <b>no</b> TTL and are never cache-reused.
 */
public enum VerificationApi {

    VERIFY_PAN("verify_pan", Duration.ofDays(365)),
    VERIFY_AADHAAR_EKYC("verify_aadhaar_ekyc", Duration.ofDays(365)),
    VERIFY_GSTIN("verify_gstin", Duration.ofDays(365)),
    FETCH_MCA21("fetch_mca21", Duration.ofDays(548)),       // ~18 months
    FETCH_GST_RETURNS("fetch_gst_returns", Duration.ofDays(90)),
    FETCH_BUREAU("fetch_bureau", Duration.ofDays(30)),
    FETCH_AA_BANK_STMT("fetch_aa_bank_stmt", Duration.ofDays(90)),
    VERIFY_PENNY_DROP("verify_penny_drop", Duration.ofDays(365)),
    VERIFY_IRN("verify_irn", null),                          // one-shot
    VERIFY_EWAY_BILL("verify_eway_bill", null),              // one-shot
    SCREEN_AML_PEP("screen_aml_pep", null);                  // one-shot

    private final String wire;
    private final Duration ttl;

    VerificationApi(String wire, Duration ttl) {
        this.wire = wire;
        this.ttl = ttl;
    }

    public String wire() {
        return wire;
    }

    /** Result lifetime, or {@code null} for a one-shot check (never cache-reused). */
    public Duration ttl() {
        return ttl;
    }

    public boolean isOneShot() {
        return ttl == null;
    }

    public static VerificationApi fromWire(String wire) {
        for (VerificationApi a : values()) {
            if (a.wire.equals(wire)) {
                return a;
            }
        }
        throw new ValidationException("unknown verification api: " + wire);
    }
}
