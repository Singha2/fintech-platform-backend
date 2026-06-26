package com.arthvritt.platform.shared;

import java.time.LocalDate;

/**
 * Shared-kernel holiday source (B1 §2). A date is a banking holiday if this returns {@code true}; weekends
 * are handled separately by {@link BusinessDate}. One implementation is wired per environment; the Phase-1
 * default ({@link ConfiguredHolidayCalendar}) is property-driven (weekend-only when unset). A richer
 * RBI/exchange holiday feed can replace it without touching any caller.
 */
public interface HolidayCalendar {

    boolean isHoliday(LocalDate date);
}
