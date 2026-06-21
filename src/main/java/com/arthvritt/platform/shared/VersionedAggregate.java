package com.arthvritt.platform.shared;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

/**
 * Base for JPA aggregate roots, mapping the schema's {@code aggregate_version} column to JPA
 * {@link Version} optimistic locking. A write that carries a stale version fails with an
 * optimistic-lock exception rather than silently clobbering a concurrent update (spec P5).
 *
 * <p>Every aggregate table in the bundle already carries {@code aggregate_version INT NOT NULL
 * DEFAULT 1}; concrete entities extend this so the pattern is inherited, not re-declared.
 * Hibernate owns the value (assigns and increments it on each flush).
 */
@MappedSuperclass
public abstract class VersionedAggregate {

    @Version
    @Column(name = "aggregate_version", nullable = false)
    private int aggregateVersion;

    public int getAggregateVersion() {
        return aggregateVersion;
    }
}
