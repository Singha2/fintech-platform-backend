package com.arthvritt.platform.shared;

import com.arthvritt.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M1b: proves the {@link VersionedAggregate} {@code @Version} pattern fires a real optimistic-lock
 * failure against the live schema (INV-1). The test method is intentionally NOT transactional, so
 * each repository call runs in its own transaction and returns a detached entity — letting us hold
 * two stale copies of one row, as two concurrent writers would.
 */
class AggregateVersionLockingTest extends AbstractIntegrationTest {

    @Autowired
    private PricingPolicyTestRepository repo;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID seededId;

    @AfterEach
    void cleanup() {
        // This test is intentionally non-transactional (it needs separate transactions to model two
        // concurrent writers), so it must delete its own row from the shared Testcontainers DB.
        if (seededId != null) {
            jdbc.update("DELETE FROM risk_pricing_policy WHERE pricing_band_id = ?", seededId);
        }
    }

    @Test
    void stale_write_fails_and_version_increments() {
        UUID id = seedPricingBand();

        // Two independent detached copies of the same row (each from its own transaction).
        PricingPolicyRow writerA = repo.findById(id).orElseThrow();
        PricingPolicyRow writerB = repo.findById(id).orElseThrow();
        assertThat(writerA.getAggregateVersion()).isEqualTo(1);

        // Writer A commits first -> version 1 -> 2.
        UUID winningBuyerId = UUID.randomUUID();
        writerA.setBuyerId(winningBuyerId);
        repo.saveAndFlush(writerA);

        // Writer B still holds version 1 -> stale write must fail, not silently overwrite.
        writerB.setBuyerId(UUID.randomUUID());
        assertThatThrownBy(() -> repo.saveAndFlush(writerB))
                .isInstanceOf(OptimisticLockingFailureException.class);

        // The lock truly rejected B: version advanced exactly once and A's value survived.
        PricingPolicyRow persisted = repo.findById(id).orElseThrow();
        assertThat(persisted.getAggregateVersion()).isEqualTo(2);
        assertThat(persisted.getBuyerId()).isEqualTo(winningBuyerId);
    }

    /** Seeds one risk_pricing_policy row via SQL (handles the enum / NOT NULL columns the entity omits). */
    private UUID seedPricingBand() {
        UUID id = UUID.randomUUID();
        seededId = id;
        jdbc.update(
                "INSERT INTO risk_pricing_policy "
                        + "(pricing_band_id, buyer_id, tenor_bucket, rate_range_min_bps, rate_range_max_bps, "
                        + " fee_bps, effective_from, aggregate_version) "
                        + "VALUES (?, ?, ?::risk_tenor_bucket, ?, ?, ?, ?, ?)",
                id, UUID.randomUUID(), "lte_30d", 100, 200, 50, Date.valueOf(LocalDate.now()), 1);
        return id;
    }
}
