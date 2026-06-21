package com.arthvritt.platform.adminiam;

/**
 * The result of a checker command (C4). {@link #APPROVED} applied the transition; {@link #BLOCKED} was
 * a maker≠checker violation — a <i>committed, audited</i> outcome (a {@code MakerChecker.Blocked}
 * envelope persists), not a rollback-reject. The HTTP layer maps {@code BLOCKED} to 409
 * {@code maker_checker_violation}.
 */
public enum MakerCheckerOutcome {
    APPROVED,
    BLOCKED
}
