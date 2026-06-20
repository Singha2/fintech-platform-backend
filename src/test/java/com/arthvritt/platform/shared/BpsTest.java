package com.arthvritt.platform.shared;

import com.arthvritt.platform.shared.error.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** M1a invariant tests for {@link Bps} (see docs/modules/M1-shared-kernel.md §7). */
class BpsTest {

    @Test
    void valid_basis_points_round_trip() {
        assertThat(Bps.of(250).value()).isEqualTo(250);
    }

    @Test
    void domain_bounds_match_bps_type() { // INV-4 — DB bps_type is [0, 100000]
        assertThat(Bps.of(0).value()).isZero();
        assertThat(Bps.of(Bps.MAX_BPS).value()).isEqualTo(100_000);
        assertThatThrownBy(() -> Bps.of(-1)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Bps.of(Bps.MAX_BPS + 1)).isInstanceOf(ValidationException.class);
    }
}
