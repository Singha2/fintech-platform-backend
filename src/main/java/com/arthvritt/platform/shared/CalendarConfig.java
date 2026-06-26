package com.arthvritt.platform.shared;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Shared-kernel time wiring (M9-B, DL-BE-040). The platform clock runs in {@code Asia/Kolkata} — business
 * days, banking cut-offs and the L.8 funding window are all reckoned in Indian time. Injecting a {@link Clock}
 * (rather than calling {@code LocalDate.now()}) keeps date-dependent handlers deterministic under test.
 */
@Configuration
public class CalendarConfig {

    public static final ZoneId PLATFORM_ZONE = ZoneId.of("Asia/Kolkata");

    @Bean
    public Clock platformClock() {
        return Clock.system(PLATFORM_ZONE);
    }
}
