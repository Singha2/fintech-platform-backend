package com.arthvritt.platform.signing;

/**
 * Lifecycle of a {@code gate_signature_session} (`vsr_status_enum`): {@code SESSION_INITIATED} →
 * {@code COMPLETED} (cert issued) / {@code FAILED} / {@code EXPIRED}. The stub only exercises the
 * happy path ({@code SESSION_INITIATED → COMPLETED}).
 */
public enum SignatureSessionStatus {
    SESSION_INITIATED("session_initiated"),
    COMPLETED("completed"),
    FAILED("failed"),
    EXPIRED("expired");

    private final String wire;

    SignatureSessionStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static SignatureSessionStatus fromWire(String wire) {
        for (SignatureSessionStatus s : values()) {
            if (s.wire.equals(wire)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown signature status: " + wire);
    }
}
