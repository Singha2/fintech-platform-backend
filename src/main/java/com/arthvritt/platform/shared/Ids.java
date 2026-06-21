package com.arthvritt.platform.shared;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;

import java.util.UUID;

/**
 * Mints platform identifiers as <b>UUIDv7</b> (time-ordered) — used for {@code event_id},
 * {@code command_id}, {@code correlation_id}, {@code causation_id} (B2 §2.1). The v7 layout puts a
 * millisecond timestamp in the high bits, so ids sort by creation time, giving the audit/event
 * chain a lexicographic tie-breaker on top of {@code occurred_at}.
 *
 * <p>JDK 21 has no built-in v7; we use the vetted {@code java-uuid-generator} (M1b, DL-BE-013).
 * The generator is thread-safe and held as a singleton.
 */
public final class Ids {

    private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

    private Ids() {
    }

    /** @return a fresh time-ordered UUIDv7. */
    public static UUID newId() {
        return GENERATOR.generate();
    }
}
