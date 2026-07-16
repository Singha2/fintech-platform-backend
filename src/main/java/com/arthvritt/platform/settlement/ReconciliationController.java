package com.arthvritt.platform.settlement;

import com.arthvritt.platform.auth.AuthSession;
import com.arthvritt.platform.infrastructure.web.ListQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BE-8 (UI_INTEGRATION_BACKEND_SPEC) — the S7 reconciliation dashboard. Additive read over
 * {@code cash_recon_ledger}, which is <b>platform-daily</b> (PK {@code business_date}), so this lists days,
 * not listings — a documented deviation from the spec's proposed {@code /listings/{id}/reconciliation}
 * (no per-listing recon table exists). Optional {@code status} filter, newest day first, {@code LIMIT 500}.
 * The variable-shape {@code discrepancies}/{@code summary} JSONB are surfaced as a {@code discrepancy_count}
 * badge (following {@link com.arthvritt.platform.listing.ListingController}'s decompose-in-SQL precedent)
 * rather than as raw blobs; a drill-down read can expand them if a screen needs the detail.
 */
@RestController
public class ReconciliationController {

    private final JdbcTemplate jdbc;

    public ReconciliationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/reconciliation")
    public List<Map<String, Object>> list(@AuthenticationPrincipal AuthSession session,
                                          @RequestParam(name = "status", required = false) String status) {
        return ListQuery.from(
                        "SELECT business_date, status::text AS status, master_statement_hash, inflows_matched, "
                                + "inflows_unmatched, updated_at, "
                                // guard the count: discrepancies has no CHECK forcing array shape, and this read
                                // does not own the (as-yet-unwritten) column — a non-array must not 500 the list.
                                + "CASE WHEN jsonb_typeof(discrepancies) = 'array' "
                                + "THEN jsonb_array_length(discrepancies) ELSE 0 END AS discrepancy_count "
                                + "FROM cash_recon_ledger")
                .eq("status", "cash_recon_ledger_status", status)
                .query(jdbc, "ORDER BY business_date DESC", (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("business_date", rs.getObject("business_date", java.time.LocalDate.class));
                    row.put("status", rs.getString("status"));
                    row.put("master_statement_hash", rs.getString("master_statement_hash"));
                    row.put("inflows_matched", rs.getInt("inflows_matched"));
                    row.put("inflows_unmatched", rs.getInt("inflows_unmatched"));
                    row.put("discrepancy_count", rs.getInt("discrepancy_count"));
                    row.put("updated_at", rs.getObject("updated_at", java.time.OffsetDateTime.class));
                    return row;
                });
    }
}
