package com.arthvritt.platform.tax;

import com.arthvritt.platform.shared.error.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * BC12 Tax — the shared, pure TDS engine (founder M16-Q8). Given a matured listing's total repayment
 * (the buyer paid the invoice {@code face_value}) and every funded investor's principal + resolved TDS
 * rate, it computes the per-investor distribution breakdown: {@code gross / interest / tds / fee / net},
 * all integer paise. No DB, no Spring — a deterministic function so the money math is unit-testable in
 * isolation and the {@code net = gross − tds − fee} invariant is proven here before the DB CHECK backstops it.
 *
 * <p><b>The pot is fixed and must reconcile to the paise.</b> The investors collectively repaid
 * {@code funding_target} (Σ principal) and the buyer repays {@code face_value}; the difference
 * {@code face_value − funding_target} is the <i>total return</i> to split. Because that pot is fixed,
 * per-investor interest is <b>allocated by largest-remainder</b> (Hamilton's method), not rounded
 * independently: each investor gets {@code floor(total_return × principal / funding_target)} and the leftover
 * paise go one-each to the largest fractional remainders (ties broken by {@code investor_id}). This guarantees
 * {@code Σ interest = total_return} exactly, hence {@code Σ gross = face_value} — the whole buyer repayment is
 * distributed, no paise created or lost (DL-BE-066).
 *
 * <p><b>TDS base = the return only</b> (founder M16-Q1), never returned principal:
 * {@code tds = round(rate_bps × interest / 10000)} HALF_EVEN, computed <i>independently</i> per investor
 * (each investor's tax is their own liability against their own income, so no cross-investor remainder
 * applies — unlike the interest split). {@code fee = 0} (founder M16-Q4), kept as a first-class field so a
 * platform fee is later a config change, not a schema change. {@code net = gross − tds − fee}.
 */
final class TaxEngine {

    private static final BigDecimal BPS_DIVISOR = BigDecimal.valueOf(10_000);

    private TaxEngine() {
    }

    /** One funded investor position going into the split: their principal and their resolved TDS rate. */
    record Position(UUID investorId, long principalPaise, int tdsRateBps) {
    }

    /** The computed per-investor distribution leg. Invariant: {@code net = gross − tds − fee}. */
    record Deduction(UUID investorId, long grossPaise, long interestPaise, long tdsPaise, long feePaise,
                     long netPaise) {
    }

    /**
     * Splits a matured listing's {@code face_value} repayment across its funded investors.
     *
     * @param faceValuePaise the buyer's full maturity repayment (= the total distribution pot).
     * @param positions      every funded investor's principal + resolved TDS rate (must be non-empty).
     * @return one {@link Deduction} per position, order preserved, with {@code Σ gross = face_value}.
     */
    static List<Deduction> distribute(long faceValuePaise, List<Position> positions) {
        if (positions.isEmpty()) {
            throw new ValidationException("no funded positions to distribute");
        }
        long fundingTarget = 0L;
        for (Position p : positions) {
            if (p.principalPaise() <= 0) {
                throw new ValidationException("investor principal must be positive: " + p.investorId());
            }
            fundingTarget = Math.addExact(fundingTarget, p.principalPaise());
        }
        long totalReturn = Math.subtractExact(faceValuePaise, fundingTarget);
        if (totalReturn < 0) {
            throw new ValidationException("face_value " + faceValuePaise + " is below Σ principal " + fundingTarget
                    + " — a maturity shortfall is not a distribution");
        }

        long[] interest = allocateInterest(totalReturn, fundingTarget, positions);

        List<Deduction> out = new ArrayList<>(positions.size());
        for (int i = 0; i < positions.size(); i++) {
            Position p = positions.get(i);
            long interestPaise = interest[i];
            long grossPaise = Math.addExact(p.principalPaise(), interestPaise);
            long tdsPaise = tdsOn(interestPaise, p.tdsRateBps());
            long feePaise = 0L; // founder M16-Q4: no platform fee at payout; field retained for configurability
            long netPaise = Math.subtractExact(Math.subtractExact(grossPaise, tdsPaise), feePaise);
            if (netPaise <= 0) { // positive_money_paise backstop — a pathological rate (>100%) is a clean reject
                throw new ValidationException("computed net payout is not positive for investor " + p.investorId()
                        + " — TDS rate too high for the return");
            }
            out.add(new Deduction(p.investorId(), grossPaise, interestPaise, tdsPaise, feePaise, netPaise));
        }
        return out;
    }

    /**
     * Largest-remainder allocation of {@code totalReturn} across positions in proportion to principal, so the
     * parts sum to {@code totalReturn} exactly. Floors first, then hands the leftover paise one-each to the
     * largest fractional remainders (ties broken deterministically by {@code investor_id}).
     */
    private static long[] allocateInterest(long totalReturn, long fundingTarget, List<Position> positions) {
        int n = positions.size();
        long[] result = new long[n];
        if (totalReturn == 0) {
            return result; // all zeros
        }
        BigDecimal total = BigDecimal.valueOf(totalReturn);
        BigDecimal target = BigDecimal.valueOf(fundingTarget);

        // Index carriers so we can rank by fractional remainder while preserving the output order.
        List<int[]> byRemainderRank = new ArrayList<>(n); // [originalIndex]
        BigDecimal[] fraction = new BigDecimal[n];
        long allocated = 0L;
        for (int i = 0; i < n; i++) {
            BigDecimal exact = total.multiply(BigDecimal.valueOf(positions.get(i).principalPaise()))
                    .divide(target, 20, RoundingMode.HALF_EVEN);
            BigDecimal floor = exact.setScale(0, RoundingMode.FLOOR);
            result[i] = floor.longValueExact();
            fraction[i] = exact.subtract(floor);
            allocated = Math.addExact(allocated, result[i]);
            byRemainderRank.add(new int[]{i});
        }
        long leftover = totalReturn - allocated; // 0 ≤ leftover < n
        byRemainderRank.sort(Comparator
                .comparing((int[] c) -> fraction[c[0]]).reversed()
                .thenComparing(c -> positions.get(c[0]).investorId()));
        for (int k = 0; k < leftover; k++) {
            result[byRemainderRank.get(k)[0]] += 1;
        }
        return result;
    }

    /** TDS on the return, HALF_EVEN to integer paise, computed independently per investor. */
    private static long tdsOn(long interestPaise, int rateBps) {
        if (interestPaise == 0 || rateBps == 0) {
            return 0L;
        }
        return BigDecimal.valueOf(interestPaise)
                .multiply(BigDecimal.valueOf(rateBps))
                .divide(BPS_DIVISOR, 0, RoundingMode.HALF_EVEN)
                .longValueExact();
    }
}
