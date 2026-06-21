package com.arthvritt.platform.verification;

/**
 * Lifecycle of a {@code gate_verification} row (`verification_status_enum`): {@code REQUESTED} →
 * {@code COMPLETED}/{@code FAILED}; {@code COMPLETED} → {@code STALE} when its TTL elapses;
 * {@code MANUAL_FALLBACK} is the Ops+Compliance escalation path (G8 — not exercised by the stub).
 */
public enum VerificationStatus {
    REQUESTED("requested"),
    COMPLETED("completed"),
    FAILED("failed"),
    STALE("stale"),
    MANUAL_FALLBACK("manual_fallback");

    private final String wire;

    VerificationStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
