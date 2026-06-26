package com.arthvritt.platform.supplier.port;

import java.util.UUID;

/**
 * BC8 Supplier read port (M9-A, DL-BE-039). The only cross-context read surface for supplier state —
 * enforced by the ArchUnit ARCH.1 rule. Read-only. Replaces the WS-4 direct read of {@code sup_account}.
 */
public interface SupplierQueryPort {

    /** The supplier's credit exposure cap in paise; rejects (validation) if none has been set. */
    long exposureCapPaise(UUID supplierId);

    /** Whether the supplier is currently {@code active} (L.11 go-live re-check). Missing supplier → false. */
    boolean isActive(UUID supplierId);
}
