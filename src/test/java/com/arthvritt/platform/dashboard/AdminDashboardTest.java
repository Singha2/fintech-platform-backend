package com.arthvritt.platform.dashboard;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-12 · {@code GET /admin/stats} + {@code GET /admin/work-queues?role=} (S2 dashboard) —
 * UI_INTEGRATION_BACKEND_SPEC §2. Counts/sums computed live from the write tables. The counts are platform-global
 * (shared container), so assertions check structure + monotonic effects of a seeded row, not exact totals.
 */
class AdminDashboardTest extends AbstractEdgeHttpTest {

    private String bearer;

    @BeforeEach
    void seed() {
        bearer = bearerFor(seedAdminWithRoles("ops_executive"));
    }

    @Test
    void stats_returns_all_headline_tiles_as_numbers() throws Exception {
        seedActiveSupplier();   // guarantees suppliers_active >= 1

        JsonNode s = node(mvc.perform(get("/admin/stats").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn());

        for (String key : List.of("active_listings", "total_deployed_paise", "investors_active",
                "suppliers_active", "pending_disbursements")) {
            assertThat(s.has(key)).as(key).isTrue();
            assertThat(s.get(key).isNumber()).as(key + " is a number").isTrue();
            assertThat(s.get(key).asLong()).as(key + " >= 0").isGreaterThanOrEqualTo(0);
        }
        assertThat(s.get("suppliers_active").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void work_queues_are_role_tagged_and_filterable() throws Exception {
        List<String> all = queueNames(query(null));
        assertThat(all).contains("supplier_kyc_review", "investor_kyc_review", "supplier_credit_review",
                "listing_ops_checks", "listing_golive_review", "disbursement_approval", "distribution_approval");

        // every row carries a stable name, an owning role, and a numeric count
        for (JsonNode row : node(query(null))) {
            assertThat(row.get("queue").isTextual()).isTrue();
            assertThat(row.get("role").isTextual()).isTrue();
            assertThat(row.get("count").isNumber()).isTrue();
        }

        // ?role= narrows to exactly that role's queues
        JsonNode compliance = node(query("compliance_reviewer"));
        assertThat(compliance.size()).isEqualTo(2);   // supplier_kyc_review + investor_kyc_review
        for (JsonNode row : compliance) {
            assertThat(row.get("role").asText()).isEqualTo("compliance_reviewer");
        }

        // an unknown role yields no queues (not an error)
        assertThat(node(query("no_such_role")).size()).isZero();
    }

    // --- helpers ----------------------------------------------------------------------------------------

    private MvcResult query(String role) throws Exception {
        var req = get("/admin/work-queues").header("Authorization", "Bearer " + bearer);
        if (role != null) {
            req = req.param("role", role);
        }
        return mvc.perform(req).andExpect(status().isOk()).andReturn();
    }

    private List<String> queueNames(MvcResult res) {
        List<String> names = new ArrayList<>();
        node(res).forEach(r -> names.add(r.get("queue").asText()));
        return names;
    }

    private void seedActiveSupplier() {
        jdbc.update("INSERT INTO sup_account (supplier_id, legal_name, constitution_type, pan, status) "
                        + "VALUES (?, 'Stats Supplier', 'private_limited'::sup_constitution_type, 'AAAAA1111A', "
                        + "'active'::sup_account_status)",
                UUID.randomUUID());
    }
}
