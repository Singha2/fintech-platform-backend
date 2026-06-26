package com.arthvritt.platform.credit;

import com.arthvritt.platform.credit.port.PricingBand;
import com.arthvritt.platform.credit.port.PricingQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * BC3 Credit read-only query port implementation (M9-A, DL-BE-039). Satisfies {@link PricingQueryPort}
 * so that the {@code listing} BC can read the active pricing band without importing any BC3 internal
 * type — the ArchUnit ARCH.1 rule enforces this boundary. Reads {@code risk_pricing_policy} via
 * native SQL; returns {@link Optional#empty()} when no active band exists for the given
 * {@code (buyerId, tenorBucket)} pair (the listing layer raises the validation error).
 */
@Service
public class PricingQueryService implements PricingQueryPort {

    private final JdbcTemplate jdbc;

    public PricingQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the non-superseded pricing band for {@code (buyerId, tenorBucket)}, or
     * {@link Optional#empty()} if none is published. {@code tenorBucket} is the
     * {@code risk_tenor_bucket} wire value (e.g. {@code "31_60d"}).
     */
    @Override
    public Optional<PricingBand> activeBand(UUID buyerId, String tenorBucket) {
        PricingBand band = jdbc.query(
                "SELECT pricing_band_id, rate_range_min_bps, rate_range_max_bps, fee_bps "
                        + "FROM risk_pricing_policy "
                        + "WHERE buyer_id = ? AND tenor_bucket = ?::risk_tenor_bucket AND superseded_by IS NULL",
                rs -> rs.next()
                        ? new PricingBand(rs.getObject("pricing_band_id", UUID.class),
                                rs.getInt("rate_range_min_bps"),
                                rs.getInt("rate_range_max_bps"),
                                rs.getInt("fee_bps"))
                        : null,
                buyerId, tenorBucket);
        return Optional.ofNullable(band);
    }
}
