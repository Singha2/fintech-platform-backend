package com.arthvritt.platform.auth;

/**
 * The two MFA-freshness bands an action can demand (B4 §6.4). {@link SessionService#isMfaFresh}
 * compares the session's assertion age against the band's window: a sensitive action (money
 * movement, IAM change) requires a much fresher assertion than a routine one.
 *
 * <p>These are the Phase-1 default windows; the final values are BC10 policy and may be tuned without
 * a schema change (DL-BE-017).
 */
public enum ActionSensitivity {

    /** Sensitive admin actions (state-changing commands): 5-minute freshness window. */
    SENSITIVE(300),

    /** Routine admin actions: 30-minute freshness window. */
    NORMAL(1800);

    private final int windowSeconds;

    ActionSensitivity(int windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public int windowSeconds() {
        return windowSeconds;
    }
}
