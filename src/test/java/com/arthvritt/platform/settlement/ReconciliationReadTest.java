package com.arthvritt.platform.settlement;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-8 · {@code GET /reconciliation?status=} (S7 dashboard) — UI_INTEGRATION_BACKEND_SPEC §2. The
 * {@code cash_recon_ledger} is platform-daily (PK {@code business_date}), so this lists days. Since the read
 * returns every day, assertions isolate the seeded rows by their (unique) business_date.
 */
class ReconciliationReadTest extends AbstractEdgeHttpTest {

    private String bearer;

    @BeforeEach
    void seed() {
        bearer = bearerFor(seedAdminWithRoles("treasury_and_settlement"));
    }

    @Test
    void lists_daily_recon_rows_with_a_discrepancy_count_badge() throws Exception {
        seedDay("2026-05-01", "completed", 42, 0, "[]");
        seedDay("2026-05-02", "completed_with_discrepancies", 40, 2,
                "[{\"va_id\":\"a\"},{\"va_id\":\"b\"}]");

        JsonNode clean = rowFor(query(null), "2026-05-01");
        assertThat(clean.get("status").asText()).isEqualTo("completed");
        assertThat(clean.get("inflows_matched").asInt()).isEqualTo(42);
        assertThat(clean.get("inflows_unmatched").asInt()).isZero();
        assertThat(clean.get("discrepancy_count").asInt()).isZero();
        assertThat(clean.hasNonNull("updated_at")).isTrue();
        assertThat(clean.has("discrepancies")).isFalse();   // raw JSONB blob is not surfaced

        JsonNode flagged = rowFor(query(null), "2026-05-02");
        assertThat(flagged.get("inflows_unmatched").asInt()).isEqualTo(2);
        assertThat(flagged.get("discrepancy_count").asInt()).isEqualTo(2);
    }

    @Test
    void filters_by_status() throws Exception {
        seedDay("2026-06-10", "open", 0, 0, "[]");
        seedDay("2026-06-11", "completed", 7, 0, "[]");

        assertThat(rowFor(query("open"), "2026-06-10")).isNotNull();
        // the completed day must not appear under ?status=open
        assertThat(hasRow(query("open"), "2026-06-11")).isFalse();
    }

    // --- helpers ----------------------------------------------------------------------------------------

    private MvcResult query(String status) throws Exception {
        var req = get("/reconciliation").header("Authorization", "Bearer " + bearer);
        if (status != null) {
            req = req.param("status", status);
        }
        return mvc.perform(req).andExpect(status().isOk()).andReturn();
    }

    private JsonNode rowFor(MvcResult res, String businessDate) {
        for (JsonNode r : node(res)) {
            if (r.get("business_date").asText().equals(businessDate)) {
                return r;
            }
        }
        throw new AssertionError("no reconciliation row for " + businessDate);
    }

    private boolean hasRow(MvcResult res, String businessDate) {
        for (JsonNode r : node(res)) {
            if (r.get("business_date").asText().equals(businessDate)) {
                return true;
            }
        }
        return false;
    }

    private void seedDay(String businessDate, String status, int matched, int unmatched, String discrepancies) {
        jdbc.update("INSERT INTO cash_recon_ledger (business_date, status, inflows_matched, inflows_unmatched, "
                        + "discrepancies) VALUES (?::date, ?::cash_recon_ledger_status, ?, ?, ?::jsonb)",
                businessDate, status, matched, unmatched, discrepancies);
    }
}
