package com.arthvritt.platform.shared;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phase-1 holiday source (M9-B, DL-BE-040): a static set of banking holidays from the
 * {@code platform.calendar.holidays} property (comma-separated ISO-8601 dates). Unset → empty → the calendar
 * is weekend-only. A documented gap: the real RBI/exchange holiday feed (which varies by year and centre)
 * replaces this implementation later without changing {@link BusinessDate} or any caller.
 */
@Component
public class ConfiguredHolidayCalendar implements HolidayCalendar {

    private final Set<LocalDate> holidays;

    public ConfiguredHolidayCalendar(@Value("${platform.calendar.holidays:}") String csv) {
        this.holidays = csv == null || csv.isBlank()
                ? Set.of()
                : Arrays.stream(csv.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(LocalDate::parse)
                        .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean isHoliday(LocalDate date) {
        return holidays.contains(date);
    }
}
