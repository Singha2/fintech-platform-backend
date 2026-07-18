package com.arthvritt.platform.listing;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-15 Part 3 (M11-C §7) — buyer self-ack. An ack-user acknowledges a listing of <b>their</b> buyer under their
 * own session — provenance stamped to the ack-user ({@code captured_by_kind='buyer_ack_user'}), own-scoped
 * ({@code cross_tenant_read} on a mismatch), {@code acknowledged}-only, requires an outstanding request (ACK-B3),
 * no double-ack (ACK-B4). Ops-on-behalf {@code record-buyer-ack} is unchanged (no S5 regression).
 */
class BuyerSelfAckTest extends AbstractEdgeHttpTest {

    private AckUserLogin ack;
    private String ackBearer;

    @BeforeEach
    void seed() {
        notifier.clear();
        ack = seedActiveAckUserWithLogin();
        ackBearer = bearerForAckUserPasswordless(ack);
    }

    @Test
    void ack_user_self_acks_its_own_buyers_listing() throws Exception {
        UUID listing = seedAwaitingAckListing(ack.buyerId(), true);
        mvc.perform(ackCommand(listing, ackBearer, Map.of("outcome", "acknowledged")))
                .andExpect(status().is2xxSuccessful());

        assertThat(buyerAck(listing, "status")).isEqualTo("acknowledged");
        assertThat(buyerAck(listing, "captured_by")).isEqualTo(ack.identityId().toString());
        assertThat(buyerAck(listing, "captured_by_kind")).isEqualTo("buyer_ack_user");
    }

    @Test
    void an_ack_user_cannot_ack_another_buyers_listing() throws Exception {
        UUID otherBuyersListing = seedAwaitingAckListing(UUID.randomUUID(), true);
        mvc.perform(ackCommand(otherBuyersListing, ackBearer, Map.of("outcome", "acknowledged")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("cross_tenant_read"));
        assertThat(buyerAck(otherBuyersListing, "status")).isEqualTo("requested"); // unchanged
    }

    @Test
    void a_self_ack_with_failed_outcome_is_rejected() throws Exception { // ACK-B2: failed stays ops-only
        UUID listing = seedAwaitingAckListing(ack.buyerId(), true);
        mvc.perform(ackCommand(listing, ackBearer, Map.of("outcome", "failed")))
                .andExpect(status().is4xxClientError());
        assertThat(buyerAck(listing, "status")).isEqualTo("requested");
    }

    @Test
    void a_self_ack_without_an_outstanding_request_is_rejected() throws Exception { // ACK-B3
        UUID listing = seedAwaitingAckListing(ack.buyerId(), false); // no buyer_ack.requested entry
        mvc.perform(ackCommand(listing, ackBearer, Map.of("outcome", "acknowledged")))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void a_second_ack_is_rejected() throws Exception { // ACK-B4 no double-ack
        UUID listing = seedAwaitingAckListing(ack.buyerId(), true);
        mvc.perform(ackCommand(listing, ackBearer, Map.of("outcome", "acknowledged")))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(ackCommand(listing, ackBearer, Map.of("outcome", "acknowledged")))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void ops_on_behalf_ack_still_works() throws Exception { // NOREG-B1
        UUID listing = seedAwaitingAckListing(ack.buyerId(), true);
        String ops = bearerFor(seedAdminWithRoles("ops_executive"));
        mvc.perform(ackCommand(listing, ops, Map.of("outcome", "acknowledged", "method", "email")))
                .andExpect(status().is2xxSuccessful());
        assertThat(buyerAck(listing, "status")).isEqualTo("acknowledged");
        assertThat(buyerAck(listing, "captured_by_kind")).isEqualTo("ops");
    }

    // --- helpers -----------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder ackCommand(UUID listing, String bearer, Map<String, Object> body)
            throws Exception {
        return post("/listings/{id}/record-buyer-ack", listing)
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", UUID.randomUUID().toString())
                .header("X-Aggregate-Version", String.valueOf(versionOf(listing)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
    }

    private int versionOf(UUID listing) {
        return jdbc.queryForObject("SELECT aggregate_version FROM deal_listing WHERE listing_id = ?",
                Integer.class, listing);
    }

    private String buyerAck(UUID listing, String field) {
        return jdbc.queryForObject("SELECT i.check_outcomes->'buyer_ack'->>? FROM deal_invoice i "
                        + "JOIN deal_listing l ON l.invoice_id = i.invoice_id WHERE l.listing_id = ?",
                String.class, field, listing);
    }
}
