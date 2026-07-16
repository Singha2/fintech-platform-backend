package com.arthvritt.platform.infrastructure.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shared assembly for the additive UI list reads (BE-4…BE-6, {@code docs/UI_INTEGRATION_BACKEND_SPEC.md}): a
 * base {@code SELECT} over a write table, zero-or-more <i>optional</i> filters, an {@code ORDER BY}, and a
 * pilot-scale row cap. It keeps the "build a {@code WHERE} from optional request params, then {@code LIMIT}"
 * boilerplate in one place so each list controller supplies only its own {@code SELECT} + {@link RowMapper}.
 *
 * <p>Every filter value is bound as a {@code ?} parameter (never string-interpolated), so callers may pass
 * raw request params without SQL-injection risk. Column/cast identifiers are caller-supplied literals, not
 * request input.
 */
public final class ListQuery {

    /** Pilot-scale cap; real pagination is deferred (spec §0.8). */
    public static final int DEFAULT_LIMIT = 500;

    private final StringBuilder sql;
    private final List<String> conditions = new ArrayList<>();
    private final List<Object> args = new ArrayList<>();

    private ListQuery(String baseSelect) {
        this.sql = new StringBuilder(baseSelect);
    }

    /** Start from a base {@code SELECT … FROM …} (may include JOINs). */
    public static ListQuery from(String baseSelect) {
        return new ListQuery(baseSelect);
    }

    /**
     * Optional equality on a string column that needs an enum/domain cast, e.g.
     * {@code eq("l.status", "deal_listing_status", status)}. A null/blank value adds no filter.
     */
    public ListQuery eq(String column, String castType, String value) {
        if (value != null && !value.isBlank()) {
            conditions.add(column + " = ?::" + castType);
            args.add(value);
        }
        return this;
    }

    /** Optional equality on a UUID column. A null value adds no filter. */
    public ListQuery eq(String column, UUID value) {
        if (value != null) {
            conditions.add(column + " = ?");
            args.add(value);
        }
        return this;
    }

    /** Optional case-insensitive substring match ({@code column ILIKE '%q%'}). A null/blank value adds no filter. */
    public ListQuery ilike(String column, String q) {
        if (q != null && !q.isBlank()) {
            conditions.add(column + " ILIKE ?");
            args.add("%" + q + "%");
        }
        return this;
    }

    /** Appends the {@code WHERE} (if any) + the given {@code ORDER BY …} + the {@code LIMIT} cap, then runs the mapped query. */
    public <T> List<T> query(JdbcTemplate jdbc, String orderBy, RowMapper<T> mapper) {
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        sql.append(' ').append(orderBy).append(" LIMIT ").append(DEFAULT_LIMIT);
        return jdbc.query(sql.toString(), mapper, args.toArray());
    }
}
