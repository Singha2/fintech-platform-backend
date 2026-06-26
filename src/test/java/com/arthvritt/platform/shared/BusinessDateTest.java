package com.arthvritt.platform.shared;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BusinessDate kernel (M9-B, DL-BE-040). Pure date arithmetic — no Spring, no DB. Proves L.8's
 * "+5 business days" skips weekends and holidays exactly.
 */
class BusinessDateTest {

    private final BusinessDate weekendOnly = new BusinessDate(date -> false);

    @Test
    void thursday_plus_five_business_days_skips_one_weekend_and_lands_on_thursday() {
        LocalDate thursday = LocalDate.of(2026, 6, 25);
        assertThat(thursday.getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY); // self-check the fixture

        LocalDate close = weekendOnly.plusBusinessDays(thursday, 5);

        assertThat(close.getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
        assertThat(close).isEqualTo(thursday.plusDays(7)); // 5 business days = 7 calendar days across one weekend
    }

    @Test
    void friday_plus_one_business_day_is_monday() {
        LocalDate friday = LocalDate.of(2026, 6, 26);
        assertThat(friday.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);

        LocalDate next = weekendOnly.plusBusinessDays(friday, 1);

        assertThat(next.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(next).isEqualTo(friday.plusDays(3));
    }

    @Test
    void a_holiday_is_skipped_not_counted() {
        LocalDate thursday = LocalDate.of(2026, 6, 25);
        LocalDate fridayHoliday = LocalDate.of(2026, 6, 26);
        BusinessDate withHoliday = new BusinessDate(Set.of(fridayHoliday)::contains);

        // Without the holiday, +5 lands Thu 07-02; the Friday holiday pushes the close one business day on
        // to Fri 07-03.
        LocalDate close = withHoliday.plusBusinessDays(thursday, 5);

        assertThat(close).isEqualTo(LocalDate.of(2026, 7, 3));
        assertThat(close.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
    }

    @Test
    void starting_on_a_weekend_does_not_count_the_start_and_advances_to_business_days() {
        LocalDate saturday = LocalDate.of(2026, 6, 27);
        assertThat(saturday.getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);

        LocalDate next = weekendOnly.plusBusinessDays(saturday, 1);

        assertThat(next.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY); // Mon 06-29
        assertThat(next).isEqualTo(LocalDate.of(2026, 6, 29));
    }

    @Test
    void is_business_day_excludes_weekends_and_holidays() {
        BusinessDate withHoliday = new BusinessDate(Set.of(LocalDate.of(2026, 6, 25))::contains);
        assertThat(withHoliday.isBusinessDay(LocalDate.of(2026, 6, 24))).isTrue();   // Wednesday
        assertThat(withHoliday.isBusinessDay(LocalDate.of(2026, 6, 25))).isFalse();  // Thursday holiday
        assertThat(withHoliday.isBusinessDay(LocalDate.of(2026, 6, 27))).isFalse();  // Saturday
    }

    @Test
    void zero_or_negative_business_days_is_rejected() {
        assertThatThrownBy(() -> weekendOnly.plusBusinessDays(LocalDate.of(2026, 6, 25), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
