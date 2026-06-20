package com.arthvritt.platform.shared;

import com.arthvritt.platform.shared.error.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** M1a invariant tests for {@link Money} (see docs/modules/M1-shared-kernel.md §7). */
class MoneyTest {

    @Test
    void paise_arithmetic_is_exact_and_value_based() {
        assertThat(Money.ofPaise(100).plus(Money.ofPaise(50))).isEqualTo(Money.ofPaise(150));
        assertThat(Money.ofPaise(150).minus(Money.ofPaise(50))).isEqualTo(Money.ofPaise(100));
        assertThat(Money.ofPaise(100).times(3)).isEqualTo(Money.ofPaise(300));
    }

    @Test
    void overflow_throws_rather_than_wrapping_silently() { // INV-2 — plus/minus/times
        assertThatThrownBy(() -> Money.ofPaise(Long.MAX_VALUE).plus(Money.ofPaise(1)))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.ofPaise(Long.MIN_VALUE).minus(Money.ofPaise(1)))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.ofPaise(Long.MIN_VALUE).times(-1)) // negation overflow edge
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void positive_context_rejects_zero_and_negative() { // INV-3 — mirrors positive_money_paise (> 0)
        assertThat(Money.ofPaise(-1).isNegative()).isTrue();
        assertThatThrownBy(() -> Money.ofPaise(-1).requirePositive())
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Money.ofPaise(0).requirePositive())
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void non_negative_context_allows_zero_but_rejects_negative() { // mirrors money_paise (>= 0)
        assertThat(Money.ofPaise(0).requireNonNegative()).isEqualTo(Money.ofPaise(0));
        assertThatThrownBy(() -> Money.ofPaise(-1).requireNonNegative())
                .isInstanceOf(ValidationException.class);
    }
}
