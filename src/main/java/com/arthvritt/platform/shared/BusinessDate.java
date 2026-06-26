package com.arthvritt.platform.shared;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Shared-kernel business-day calculator (B1 §2; M9-B, DL-BE-040). A business day is a weekday that is not a
 * {@link HolidayCalendar} holiday. Used by Listing for the L.8 5-business-day funding window, and later by
 * Settlement (C11 T+1) and Collections. Pure date arithmetic — no clock, no I/O — so it unit-tests with
 * fixed dates and composes with whatever "today" a caller supplies.
 */
@Component
public class BusinessDate {

    private final HolidayCalendar holidays;

    public BusinessDate(HolidayCalendar holidays) {
        this.holidays = holidays;
    }

    /** A weekday (Mon–Fri) that is not a holiday. */
    public boolean isBusinessDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.isHoliday(date);
    }

    /**
     * The date that is {@code businessDays} business days strictly after {@code start} (the start date itself
     * is never counted, regardless of whether it is a business day). Weekends and holidays are skipped, not
     * counted. {@code businessDays} must be {@code >= 1}.
     *
     * <p>Example (L.8): a go-live on a Thursday + 5 business days → the following Thursday (one weekend
     * skipped, 7 calendar days).
     */
    public LocalDate plusBusinessDays(LocalDate start, int businessDays) {
        if (businessDays < 1) {
            throw new IllegalArgumentException("businessDays must be >= 1, was " + businessDays);
        }
        LocalDate date = start;
        int remaining = businessDays;
        while (remaining > 0) {
            date = date.plusDays(1);
            if (isBusinessDay(date)) {
                remaining--;
            }
        }
        return date;
    }
}
