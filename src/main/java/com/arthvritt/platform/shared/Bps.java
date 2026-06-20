package com.arthvritt.platform.shared;

import com.arthvritt.platform.shared.error.ValidationException;

/**
 * A rate in integer <b>basis points</b> (Constitution: "Rates are bps") — never a float.
 * Mirrors the DB {@code bps_type} domain: {@code 0 <= value <= 100000} (i.e. 0%–1000%).
 * 1 bps = 0.01%. Read the value via the record accessor {@link #value()}.
 */
public record Bps(int value) {

    /** Upper bound from the DB {@code bps_type} domain ({@code VALUE <= 100000}). */
    public static final int MAX_BPS = 100_000;

    public Bps {
        if (value < 0 || value > MAX_BPS) {
            throw new ValidationException(
                    "basis points must be within [0, " + MAX_BPS + "], was " + value);
        }
    }

    public static Bps of(int value) {
        return new Bps(value);
    }

    @Override
    public String toString() {
        return value + " bps";
    }
}
