package com.arthvritt.platform.shared;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Test-only repository for {@link PricingPolicyRow} (M1b optimistic-locking test). */
interface PricingPolicyTestRepository extends JpaRepository<PricingPolicyRow, UUID> {
}
