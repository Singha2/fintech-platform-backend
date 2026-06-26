package com.arthvritt.platform.supplier;

import com.arthvritt.platform.shared.error.ValidationException;
import com.arthvritt.platform.supplier.port.SupplierQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * BC8 Supplier read-only query port implementation (M9-A, DL-BE-039). Satisfies {@link SupplierQueryPort}
 * so that the {@code listing} BC can read supplier state without importing any BC8 internal type —
 * the ArchUnit ARCH.1 rule enforces this boundary. All reads are native SQL via {@link JdbcTemplate};
 * absent-row cases return a safe default (false / validation exception) rather than letting
 * {@code EmptyResultDataAccessException} bubble as a 500.
 */
@Service
public class SupplierQueryService implements SupplierQueryPort {

    private final JdbcTemplate jdbc;

    public SupplierQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the credit exposure cap in paise. Throws {@link ValidationException} if the supplier
     * row does not exist or the {@code credit_exposure_cap_paise} column is null (not yet set).
     */
    @Override
    public long exposureCapPaise(UUID supplierId) {
        Long v = jdbc.query(
                "SELECT credit_exposure_cap_paise FROM sup_account WHERE supplier_id = ?",
                rs -> rs.next() ? (Long) rs.getObject("credit_exposure_cap_paise") : null,
                supplierId);
        if (v == null) {
            throw new ValidationException("supplier has no exposure cap set: " + supplierId);
        }
        return v;
    }

    /**
     * Returns {@code true} iff the supplier row exists and its status is {@code active}.
     * A missing supplier row returns {@code false} rather than throwing.
     */
    @Override
    public boolean isActive(UUID supplierId) {
        String status = jdbc.query(
                "SELECT status::text FROM sup_account WHERE supplier_id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                supplierId);
        return "active".equals(status);
    }
}
