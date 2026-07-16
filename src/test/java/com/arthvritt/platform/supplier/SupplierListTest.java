package com.arthvritt.platform.supplier;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-4 · {@code GET /suppliers} — the S3 supplier list (UI_INTEGRATION_BACKEND_SPEC §2). Additive read over
 * {@code sup_account} with optional {@code status}/{@code q} filters. Rows are isolated by a unique marker in
 * the legal name (via {@code ?q=}), so the assertions are robust against any other seeded data.
 */
class SupplierListTest extends AbstractEdgeHttpTest {

    private static final Random RND = new Random();
    private String bearer;

    @BeforeEach
    void seed() {
        bearer = bearerFor(seedAdminWithRoles("ops_executive"));
    }

    @Test
    void list_returns_matching_suppliers_with_their_display_columns() throws Exception {
        String marker = marker();
        UUID active = seedSupplier(marker + " Alpha Textiles", "active");
        UUID draft = seedSupplier(marker + " Beta Traders", "created");

        List<JsonNode> rows = rows(query(marker, null));

        assertThat(idsOf(rows)).containsExactlyInAnyOrder(active.toString(), draft.toString());
        JsonNode alpha = rows.stream().filter(r -> r.get("supplier_id").asText().equals(active.toString()))
                .findFirst().orElseThrow();
        assertThat(alpha.get("legal_name").asText()).contains("Alpha Textiles");
        assertThat(alpha.get("constitution_type").asText()).isEqualTo("private_limited");
        assertThat(alpha.get("status").asText()).isEqualTo("active");
        assertThat(alpha.hasNonNull("pan")).isTrue();
        assertThat(alpha.has("gstin")).isTrue();          // present even when null
        assertThat(alpha.has("activated_at")).isTrue();
    }

    @Test
    void the_status_filter_narrows_the_list() throws Exception {
        String marker = marker();
        UUID active = seedSupplier(marker + " Active Co", "active");
        seedSupplier(marker + " Created Co", "created");

        List<JsonNode> activeRows = rows(query(marker, "active"));

        assertThat(idsOf(activeRows)).containsExactly(active.toString());
    }

    // --- helpers ----------------------------------------------------------------------------------------

    private MvcResult query(String q, String status) throws Exception {
        var req = get("/suppliers").header("Authorization", "Bearer " + bearer).param("q", q);
        if (status != null) {
            req = req.param("status", status);
        }
        return mvc.perform(req).andExpect(status().isOk()).andReturn();
    }

    private List<JsonNode> rows(MvcResult res) {
        List<JsonNode> out = new ArrayList<>();
        node(res).forEach(out::add);
        return out;
    }

    private static List<String> idsOf(List<JsonNode> rows) {
        List<String> ids = new ArrayList<>();
        rows.forEach(r -> ids.add(r.get("supplier_id").asText()));
        return ids;
    }

    private UUID seedSupplier(String legalName, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sup_account (supplier_id, legal_name, constitution_type, pan, status) "
                        + "VALUES (?, ?, 'private_limited', ?::pan_type, ?::sup_account_status)",
                id, legalName, randomPan(), status);
        return id;
    }

    private static String marker() {
        return "MK" + Long.toHexString(RND.nextLong() & 0xFFFFFFFFL).toUpperCase();
    }

    /** A syntactically valid, effectively-unique PAN ({@code [A-Z]{5}[0-9]{4}[A-Z]}). */
    private static String randomPan() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append((char) ('A' + RND.nextInt(26)));
        }
        for (int i = 0; i < 4; i++) {
            sb.append(RND.nextInt(10));
        }
        return sb.append((char) ('A' + RND.nextInt(26))).toString();
    }
}
