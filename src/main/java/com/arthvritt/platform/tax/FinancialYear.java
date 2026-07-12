package com.arthvritt.platform.tax;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Indian financial-year code (April–March). {@code FY2026-27} runs 2026-04-01 … 2027-03-31. All tax records
 * are keyed by this code (matches {@code tax_year_profile.fy_code} / {@code tax_rate_default.fy_code}).
 * Time is Asia/Kolkata, per the money-time convention.
 */
final class FinancialYear {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private FinancialYear() {
    }

    /** The FY code for {@code date} — e.g. 2026-07-12 → {@code FY2026-27}, 2027-02-01 → {@code FY2026-27}. */
    static String of(LocalDate date) {
        int startYear = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
        return String.format("FY%d-%02d", startYear, (startYear + 1) % 100);
    }

    /** The current FY code in Asia/Kolkata. */
    static String current() {
        return of(LocalDate.now(IST));
    }
}
