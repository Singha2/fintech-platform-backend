package com.arthvritt.platform.listing;

import com.arthvritt.platform.shared.error.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The {@code funding_target} computation (L.7 / DL-024), frozen at the pricing snapshot. All money is
 * integer paise; rates are basis points.
 *
 * <pre>
 *   discount = face_value × rate_bps / 10000 × tenor_days / 365
 *   fee      = face_value × fee_bps  / 10000
 *   funding_target = face_value − round(discount) − round(fee)
 * </pre>
 *
 * <p><b>Rounding (DL-BE-034):</b> discount and fee are <i>separate reported line items</i> (the fee
 * resurfaces in distribution/TDS), so each is rounded independently to integer paise with
 * {@link RoundingMode#HALF_EVEN} (banker's rounding — unbiased over many invoices, no systematic drift to
 * platform or supplier). {@link BigDecimal} carries the products exactly (face_value can be ~10^9 paise, so
 * the intermediate face_value × bps × tenor exceeds a scaled {@code long}); no floating point touches money.
 */
final class FundingMath {

    private static final BigDecimal BPS_DIVISOR = BigDecimal.valueOf(10_000);
    private static final BigDecimal DAYS_IN_YEAR = BigDecimal.valueOf(365);

    private FundingMath() {
    }

    /** The discount withheld (the investor's gross yield), in integer paise, HALF_EVEN. */
    private static BigDecimal discount(long faceValuePaise, int rateBps, int tenorDays) {
        return BigDecimal.valueOf(faceValuePaise)
                .multiply(BigDecimal.valueOf(rateBps))
                .multiply(BigDecimal.valueOf(tenorDays))
                .divide(BPS_DIVISOR.multiply(DAYS_IN_YEAR), 0, RoundingMode.HALF_EVEN);
    }

    /** The platform fee, in integer paise, HALF_EVEN. */
    private static BigDecimal fee(long faceValuePaise, int feeBps) {
        return BigDecimal.valueOf(faceValuePaise)
                .multiply(BigDecimal.valueOf(feeBps))
                .divide(BPS_DIVISOR, 0, RoundingMode.HALF_EVEN);
    }

    /**
     * {@code funding_target = face_value − round(discount) − round(fee)}, each line item rounded HALF_EVEN
     * (DL-BE-034). Must be strictly positive ({@code positive_money_paise}); a rate/fee that would zero or
     * invert it (e.g. an absurdly high rate making discount {@literal >} face_value) is a rejected input —
     * a clean 400, never an overflow or DB-domain 500. The arithmetic stays in {@link BigDecimal} (a
     * line-item discount can exceed {@code long} for an out-of-range rate); only the validated result —
     * guaranteed in {@code (0, face_value]} — is narrowed to {@code long}.
     */
    static long fundingTargetPaise(long faceValuePaise, int rateBps, int tenorDays, int feeBps) {
        BigDecimal target = BigDecimal.valueOf(faceValuePaise)
                .subtract(discount(faceValuePaise, rateBps, tenorDays))
                .subtract(fee(faceValuePaise, feeBps));
        if (target.signum() <= 0) {
            throw new ValidationException("computed funding_target is not positive — rate/fee too high for the face value");
        }
        return target.longValueExact(); // 0 < target ≤ face_value ≤ Long.MAX, so this never overflows
    }
}
