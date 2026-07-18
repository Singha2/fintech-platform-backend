package com.arthvritt.platform.buyer;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-15 Part 2 (M11-C §7) — own-scoped buyer reads. An ack-user reads its own buyer's invoices awaiting
 * acknowledgment + payment instruction; a mismatched buyer id → 403 {@code cross_tenant_read} and is audited
 * ({@code buyer.CrossTenantReadDenied}). A successful own read writes no audit.
 */
class BuyerPortalReadTest extends AbstractEdgeHttpTest {

    @Test
    void ack_user_reads_its_own_buyers_awaiting_ack_invoices() throws Exception {
        AckUserLogin ack = seedActiveAckUserWithLogin();
        UUID listingId = seedAwaitingAckListing(ack.buyerId(), true);
        String bearer = bearerForAckUserPasswordless(ack);

        mvc.perform(get("/buyers/{id}/ack-invoices", ack.buyerId()).header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].listing_id").value(listingId.toString()))
                .andExpect(jsonPath("$[0].ack_status").value("requested"))
                .andExpect(jsonPath("$[0].supplier_name").exists())
                .andExpect(jsonPath("$[0].aggregate_version").exists());
    }

    @Test
    void a_cross_buyer_read_is_403_and_audited() throws Exception {
        AckUserLogin ack = seedActiveAckUserWithLogin();
        UUID otherBuyer = seedActiveAckUserWithLogin().buyerId();
        String bearer = bearerForAckUserPasswordless(ack);

        mvc.perform(get("/buyers/{id}/ack-invoices", otherBuyer).header("Authorization", "Bearer " + bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("cross_tenant_read"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_event "
                        + "WHERE event_type = 'buyer.CrossTenantReadDenied' AND actor->>'actor_id' = ? "
                        + "AND payload->>'attempted_buyer_id' = ?",
                Integer.class, ack.identityId().toString(), otherBuyer.toString())).isEqualTo(1);
    }

    @Test
    void an_own_read_writes_no_denial_audit() throws Exception {
        AckUserLogin ack = seedActiveAckUserWithLogin();
        seedAwaitingAckListing(ack.buyerId(), true);
        String bearer = bearerForAckUserPasswordless(ack);

        mvc.perform(get("/buyers/{id}/ack-invoices", ack.buyerId()).header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_event "
                        + "WHERE event_type = 'buyer.CrossTenantReadDenied' AND actor->>'actor_id' = ?",
                Integer.class, ack.identityId().toString())).isZero();
    }

    @Test
    void ack_user_reads_its_payment_instruction_metadata() throws Exception {
        AckUserLogin ack = seedActiveAckUserWithLogin();
        UUID admin = seedAdminWithRoles().adminUserId();
        jdbc.update("INSERT INTO buyer_payment_rule (instruction_id, buyer_id, instruction_doc_hash, "
                        + "effective_from, confirmed_by) VALUES (?, ?, ?, now()::date, ?)",
                UUID.randomUUID(), ack.buyerId(), "hash".getBytes(java.nio.charset.StandardCharsets.UTF_8), admin);
        String bearer = bearerForAckUserPasswordless(ack);

        mvc.perform(get("/buyers/{id}/payment-instruction", ack.buyerId())
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present").value(true))
                .andExpect(jsonPath("$.effective_from").exists());
    }
}
