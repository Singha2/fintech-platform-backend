package com.arthvritt.platform.tax;

import com.arthvritt.platform.shared.error.ValidationException;
import com.arthvritt.platform.tax.TaxEngine.Deduction;
import com.arthvritt.platform.tax.TaxEngine.Position;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure money-math proof for the M16 TDS engine (no DB). Every case asserts the two load-bearing invariants:
 * {@code Σ gross = face_value} (the whole buyer repayment is distributed, no paise lost) and, per investor,
 * {@code net = gross − tds − fee}. TDS is on the return only, HALF_EVEN, per-investor rate.
 */
class TaxEngineTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

    @Test
    void single_investor_matches_the_founder_example() {
        // ₹10,00,000 face; investor funded ₹9,60,274; return ₹39,726; TDS @10% = ₹3,972.60.
        long face = 100_000_000L;      // ₹10,00,000 in paise
        long principal = 96_027_400L;  // ₹9,60,274
        List<Deduction> out = TaxEngine.distribute(face, List.of(new Position(A, principal, 1000)));

        Deduction d = out.get(0);
        assertThat(d.interestPaise()).isEqualTo(3_972_600L);
        assertThat(d.grossPaise()).isEqualTo(face);
        assertThat(d.tdsPaise()).isEqualTo(397_260L);   // 10% of the return
        assertThat(d.feePaise()).isZero();
        assertThat(d.netPaise()).isEqualTo(face - 397_260L);
        assertNetFormula(d);
    }

    @Test
    void pan_absent_is_taxed_at_the_higher_206AA_rate() {
        long face = 100_000_000L;
        long principal = 96_027_400L;
        long interest = face - principal;

        long withPan = TaxEngine.distribute(face, List.of(new Position(A, principal, 1000))).get(0).tdsPaise();
        long withoutPan = TaxEngine.distribute(face, List.of(new Position(A, principal, 2000))).get(0).tdsPaise();

        assertThat(withPan).isEqualTo(Math.round(interest * 0.10));
        assertThat(withoutPan).isEqualTo(Math.round(interest * 0.20));
        assertThat(withoutPan).isEqualTo(2 * withPan);
    }

    @Test
    void multi_investor_split_reconciles_to_the_paise() {
        // A funded 1/3, B funded 2/3 of a target that does not divide the return evenly → remainder handled.
        long face = 100_000_001L;      // odd face to force a leftover paise
        List<Position> positions = List.of(
                new Position(A, 33_000_000L, 1000),
                new Position(B, 66_000_000L, 2000));
        List<Deduction> out = TaxEngine.distribute(face, positions);

        assertThat(sumGross(out)).isEqualTo(face); // the whole buyer repayment is distributed
        out.forEach(TaxEngineTest::assertNetFormula);
        // Each investor's gross = their principal + their allocated return.
        assertThat(out.get(0).grossPaise()).isEqualTo(33_000_000L + out.get(0).interestPaise());
        assertThat(out.get(1).grossPaise()).isEqualTo(66_000_000L + out.get(1).interestPaise());
        // TDS uses each investor's own rate on their own return.
        assertThat(out.get(0).tdsPaise()).isEqualTo(halfEvenBps(out.get(0).interestPaise(), 1000));
        assertThat(out.get(1).tdsPaise()).isEqualTo(halfEvenBps(out.get(1).interestPaise(), 2000));
    }

    @Test
    void leftover_paise_go_to_the_largest_remainders_deterministically() {
        // Three equal principals, a return of 100 paise: 100/3 = 33.33 each → 33,33,33 + 1 leftover paise.
        long face = 30_000_100L; // Σ principal 30_000_000 + 100 return
        List<Position> positions = List.of(
                new Position(A, 10_000_000L, 0),
                new Position(B, 10_000_000L, 0),
                new Position(C, 10_000_000L, 0));
        List<Deduction> out = TaxEngine.distribute(face, positions);

        assertThat(sumGross(out)).isEqualTo(face);
        assertThat(out.stream().mapToLong(Deduction::interestPaise).sum()).isEqualTo(100L);
        // Equal remainders (all .333…) → tie broken by investor_id ascending; A < B < C so A takes the paise.
        assertThat(out.get(0).interestPaise()).isEqualTo(34L);
        assertThat(out.get(1).interestPaise()).isEqualTo(33L);
        assertThat(out.get(2).interestPaise()).isEqualTo(33L);
    }

    @Test
    void zero_return_yields_zero_tds_and_net_equals_principal() {
        long face = 50_000_000L;
        List<Deduction> out = TaxEngine.distribute(face, List.of(new Position(A, face, 1000)));
        Deduction d = out.get(0);
        assertThat(d.interestPaise()).isZero();
        assertThat(d.tdsPaise()).isZero();
        assertThat(d.netPaise()).isEqualTo(face);
        assertNetFormula(d);
    }

    @Test
    void face_below_principal_is_rejected() {
        assertThatThrownBy(() -> TaxEngine.distribute(40_000_000L, List.of(new Position(A, 50_000_000L, 1000))))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void empty_positions_are_rejected() {
        assertThatThrownBy(() -> TaxEngine.distribute(100_000_000L, List.of()))
                .isInstanceOf(ValidationException.class);
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static void assertNetFormula(Deduction d) {
        assertThat(d.netPaise()).isEqualTo(d.grossPaise() - d.tdsPaise() - d.feePaise());
    }

    private static long sumGross(List<Deduction> out) {
        return out.stream().mapToLong(Deduction::grossPaise).sum();
    }

    private static long halfEvenBps(long amount, int bps) {
        return java.math.BigDecimal.valueOf(amount).multiply(java.math.BigDecimal.valueOf(bps))
                .divide(java.math.BigDecimal.valueOf(10_000), 0, java.math.RoundingMode.HALF_EVEN).longValueExact();
    }
}
