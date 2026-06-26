package com.arthvritt.platform.buyer;

import com.arthvritt.platform.buyer.port.BuyerQueryPort;
import com.arthvritt.platform.shared.error.ValidationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * BC9 Buyer read-only query port implementation (M9-A, DL-BE-039). Satisfies {@link BuyerQueryPort}
 * so that the {@code listing} BC can read buyer state without importing any BC9 internal type —
 * the ArchUnit ARCH.1 rule enforces this boundary. All reads are native SQL via {@link JdbcTemplate};
 * absent-row cases return a safe default (false / validation exception) rather than letting
 * {@code EmptyResultDataAccessException} bubble as a 500.
 */
@Service
public class BuyerQueryService implements BuyerQueryPort {

    private final JdbcTemplate jdbc;

    public BuyerQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the assessed credit limit in paise. Throws {@link ValidationException} if the buyer
     * row does not exist or the {@code credit_limit_paise} column is null (not yet assessed).
     */
    @Override
    public long creditLimitPaise(UUID buyerId) {
        Long v = jdbc.query(
                "SELECT credit_limit_paise FROM buyer_account WHERE buyer_id = ?",
                rs -> rs.next() ? (Long) rs.getObject("credit_limit_paise") : null,
                buyerId);
        if (v == null) {
            throw new ValidationException("buyer has no credit limit set: " + buyerId);
        }
        return v;
    }

    /**
     * Returns {@code true} iff the buyer row exists and its status is {@code active}.
     * A missing buyer row returns {@code false} rather than throwing.
     */
    @Override
    public boolean isActive(UUID buyerId) {
        String status = jdbc.query(
                "SELECT status::text FROM buyer_account WHERE buyer_id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                buyerId);
        return "active".equals(status);
    }

    /**
     * The identity id of the buyer's active acknowledgment user (lowest {@code ack_user_id} if several),
     * or empty when the buyer has no active ack user. Owns the {@code buyer_ack_user} read on the BC9 side.
     */
    @Override
    public Optional<UUID> activeAckUserIdentity(UUID buyerId) {
        UUID identityId = jdbc.query(
                "SELECT identity_id FROM buyer_ack_user WHERE buyer_id = ? AND is_active = TRUE "
                        + "ORDER BY ack_user_id LIMIT 1",
                rs -> rs.next() ? rs.getObject("identity_id", UUID.class) : null,
                buyerId);
        return Optional.ofNullable(identityId);
    }
}
