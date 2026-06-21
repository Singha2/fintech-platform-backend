package com.arthvritt.platform.shared;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Test-only entity mapping a minimal slice of {@code risk_pricing_policy} (an existing aggregate
 * table) onto {@link VersionedAggregate}, to prove the optimistic-locking pattern against the real
 * schema. Maps only {@code pricing_band_id} (@Id), {@code buyer_id} (a mutable, FK-free column), and
 * the inherited {@code aggregate_version} (@Version) — so Hibernate {@code ddl-auto=validate} only
 * has UUID/INT columns to check (no Postgres enum / domain types). Rows are seeded via SQL in the
 * test, so the unmapped NOT NULL columns are not Hibernate's concern.
 */
@Entity
@Table(name = "risk_pricing_policy")
public class PricingPolicyRow extends VersionedAggregate {

    @Id
    @Column(name = "pricing_band_id")
    private UUID pricingBandId;

    @Column(name = "buyer_id")
    private UUID buyerId;

    protected PricingPolicyRow() {
    }

    public UUID getPricingBandId() {
        return pricingBandId;
    }

    public UUID getBuyerId() {
        return buyerId;
    }

    public void setBuyerId(UUID buyerId) {
        this.buyerId = buyerId;
    }
}
