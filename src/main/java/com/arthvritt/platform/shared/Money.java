package com.arthvritt.platform.shared;

import com.arthvritt.platform.shared.error.ValidationException;

/**
 * Money as integer <b>paise</b> — never a float (Constitution: "Money is paise (BIGINT)").
 *
 * <p>A {@code Money} may be negative (it maps to a signed {@code BIGINT}); negative deltas
 * are legitimate in some ledger contexts. The DB's {@code money_paise} domain is {@code >= 0}
 * and {@code positive_money_paise} is {@code > 0} — call {@link #requirePositive()} at the
 * boundary of a strictly-positive context to mirror the latter before persisting.
 *
 * <p>Arithmetic uses {@link Math#addExact}/{@code subtractExact}/{@code multiplyExact} so a
 * {@code long} overflow throws {@link ArithmeticException} rather than wrapping silently.
 */
public record Money(long paise) {

    public static Money ofPaise(long paise) {
        return new Money(paise);
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(paise, other.paise));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(paise, other.paise));
    }

    public Money times(long factor) {
        return new Money(Math.multiplyExact(paise, factor));
    }

    public boolean isNegative() {
        return paise < 0;
    }

    /**
     * Asserts a strictly-positive amount, mirroring the DB {@code positive_money_paise}
     * domain ({@code > 0}; zero is an accounting error). Use at boundaries that persist to a
     * {@code positive_money_paise} column (gross/net payouts, credit limits).
     *
     * @return this, for chaining
     * @throws ValidationException if the amount is zero or negative
     */
    public Money requirePositive() {
        if (paise <= 0) {
            throw new ValidationException("amount must be strictly positive (paise > 0), was " + paise);
        }
        return this;
    }

    /**
     * Asserts a non-negative amount, mirroring the DB {@code money_paise} domain ({@code >= 0}).
     * Use at boundaries that persist to a {@code money_paise} column (where zero is valid).
     *
     * @return this, for chaining
     * @throws ValidationException if the amount is negative
     */
    public Money requireNonNegative() {
        if (paise < 0) {
            throw new ValidationException("amount must be non-negative (paise >= 0), was " + paise);
        }
        return this;
    }

    @Override
    public String toString() {
        return "Money[" + paise + " paise]";
    }
}
