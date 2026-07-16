package com.arthvritt.platform.settlement;

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
 * BE-7 · {@code GET /disbursements?status=} (S6 queue) + {@code GET /listings/{id}/disbursement/detail}
 * (richer than the frozen by-id) — UI_INTEGRATION_BACKEND_SPEC §2. Reads over {@code cash_payout_instruction}
 * (kind {@code disbursement}) JOINed to its listing. Rows isolated by unique listing ids per test.
 */
class DisbursementReadTest extends AbstractEdgeHttpTest {

    private String bearer;
    private UUID maker;
    private UUID checker;   // a distinct admin (PI.5: maker ≠ checker)

    @BeforeEach
    void seed() {
        Seeded makerAdmin = seedAdminWithRoles("treasury_and_settlement");
        bearer = bearerFor(makerAdmin);
        maker = makerAdmin.adminUserId();
        checker = seedAdminWithRoles("treasury_and_settlement").adminUserId();
    }

    @Test
    void queue_lists_disbursement_instructions_filtered_by_status() throws Exception {
        UUID drafted = seedDisbursement(newListing(), "drafted", 10_00_000_00L, 9_90_000_00L, 10_000_00L, null);
        UUID approved = seedDisbursement(newListing(), "approved", 5_00_000_00L, 4_95_000_00L, 5_000_00L, checker);

        List<JsonNode> draftedRows = matching(query("drafted"), drafted);
        assertThat(draftedRows).hasSize(1);
        JsonNode row = draftedRows.get(0);
        assertThat(row.get("status").asText()).isEqualTo("drafted");
        assertThat(row.get("gross_amount").asLong()).isEqualTo(10_00_000_00L);
        assertThat(row.get("net_amount").asLong()).isEqualTo(9_90_000_00L);
        assertThat(row.get("maker_id").asText()).isEqualTo(maker.toString());
        assertThat(row.get("checker_id").isNull()).isTrue();      // not yet approved
        assertThat(row.hasNonNull("listing_status")).isTrue();

        // the approved one must not appear under ?status=drafted
        assertThat(matching(query("drafted"), approved)).isEmpty();
    }

    @Test
    void detail_returns_the_full_instruction_plus_listing_status() throws Exception {
        UUID listing = newListing();
        seedDisbursement(listing, "approved", 8_00_000_00L, 7_84_000_00L, 16_000_00L, checker);

        JsonNode row = node(mvc.perform(get("/listings/{id}/disbursement/detail", listing)
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn());

        assertThat(row.get("status").asText()).isEqualTo("approved");
        assertThat(row.get("gross_amount").asLong()).isEqualTo(8_00_000_00L);
        assertThat(row.get("net_amount").asLong()).isEqualTo(7_84_000_00L);
        assertThat(row.get("fee_amount").asLong()).isEqualTo(16_000_00L);
        assertThat(row.get("maker_id").asText()).isEqualTo(maker.toString());
        assertThat(row.get("checker_id").asText()).isEqualTo(checker.toString());
        assertThat(row.has("listing_status")).isTrue();
        assertThat(row.has("total_tds_amount")).isTrue();   // present even when null (disbursements carry no TDS)
    }

    @Test
    void detail_for_a_listing_with_no_disbursement_is_404() throws Exception {
        mvc.perform(get("/listings/{id}/disbursement/detail", newListing())
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isNotFound());
    }

    // --- helpers ----------------------------------------------------------------------------------------

    private MvcResult query(String status) throws Exception {
        return mvc.perform(get("/disbursements").header("Authorization", "Bearer " + bearer).param("status", status))
                .andExpect(status().isOk()).andReturn();
    }

    /** Rows from a queue response whose payout_instruction_id equals the given id (isolates our seeded row). */
    private List<JsonNode> matching(MvcResult res, UUID payoutInstructionId) {
        List<JsonNode> out = new ArrayList<>();
        node(res).forEach(r -> {
            if (r.get("payout_instruction_id").asText().equals(payoutInstructionId.toString())) {
                out.add(r);
            }
        });
        return out;
    }

    /** A disbursement instruction; when {@code checkerId} is set, also stamps the required MFA assertion (PI.5). */
    private UUID seedDisbursement(UUID listingId, String status, long gross, long net, long fee, UUID checkerId) {
        UUID payoutId = UUID.randomUUID();
        jdbc.update("INSERT INTO cash_payout_instruction (payout_instruction_id, kind, listing_id, status, "
                        + "gross_amount, net_amount, fee_amount, maker_id, checker_id, checker_mfa_assertion_id) "
                        + "VALUES (?, 'disbursement'::cash_payout_kind, ?, ?::cash_payout_status, ?, ?, ?, ?, ?, ?)",
                payoutId, listingId, status, gross, net, fee, maker, checkerId,
                checkerId == null ? null : "mfa-" + UUID.randomUUID());
        return payoutId;
    }

    /** A minimal invoice + listing so the disbursement JOIN resolves; returns the listing id. */
    private UUID newListing() {
        UUID supplier = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        jdbc.update("INSERT INTO deal_invoice (invoice_id, supplier_id, buyer_id, invoice_number, face_value, "
                        + "invoice_date, tenor_days, due_date) "
                        + "VALUES (?, ?, ?, ?, ?, '2026-01-15', 45, '2026-03-01')",
                invoiceId, supplier, buyer, "INV-" + UUID.randomUUID(), 10_00_000_00L);
        UUID listingId = UUID.randomUUID();
        jdbc.update("INSERT INTO deal_listing (listing_id, invoice_id, supplier_id, buyer_id, status) "
                        + "VALUES (?, ?, ?, ?, 'fully_funded'::deal_listing_status)",
                listingId, invoiceId, supplier, buyer);
        return listingId;
    }
}
