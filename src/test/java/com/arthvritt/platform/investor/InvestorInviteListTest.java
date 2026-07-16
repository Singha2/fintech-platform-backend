package com.arthvritt.platform.investor;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-9 · {@code GET /investor-invites?status=} (S3 tracker) — UI_INTEGRATION_BACKEND_SPEC §2. Additive read
 * over {@code inv_invite}; the {@code email_hash}/{@code phone_hash} PII columns are never surfaced. Since the
 * read returns every invite, assertions isolate the seeded rows by their invite id.
 */
class InvestorInviteListTest extends AbstractEdgeHttpTest {

    private String bearer;
    private UUID issuer;   // a real admin_user (inv_invite.issued_by has an enforced FK)

    @BeforeEach
    void seed() {
        Seeded admin = seedAdminWithRoles("compliance_reviewer");
        bearer = bearerFor(admin);
        issuer = admin.adminUserId();
    }

    @Test
    void lists_invites_without_pii() throws Exception {
        UUID pending = seedInvite("pending");

        JsonNode row = rowFor(query(null), pending);
        assertThat(row.get("status").asText()).isEqualTo("pending");
        assertThat(row.get("issued_by").asText()).isEqualTo(issuer.toString());
        assertThat(row.hasNonNull("issued_at")).isTrue();
        assertThat(row.hasNonNull("expiry_at")).isTrue();
        assertThat(row.get("consumed_at").isNull()).isTrue();   // present but null for a pending invite
        // PII hashes must never be surfaced
        assertThat(row.has("email_hash")).isFalse();
        assertThat(row.has("phone_hash")).isFalse();
    }

    @Test
    void filters_by_status() throws Exception {
        UUID pending = seedInvite("pending");
        UUID expired = seedInvite("expired");

        assertThat(hasRow(query("expired"), expired)).isTrue();
        // the pending invite must not appear under ?status=expired
        assertThat(hasRow(query("expired"), pending)).isFalse();
    }

    // --- helpers ----------------------------------------------------------------------------------------

    private MvcResult query(String status) throws Exception {
        var req = get("/investor-invites").header("Authorization", "Bearer " + bearer);
        if (status != null) {
            req = req.param("status", status);
        }
        return mvc.perform(req).andExpect(status().isOk()).andReturn();
    }

    private JsonNode rowFor(MvcResult res, UUID inviteId) {
        for (JsonNode r : node(res)) {
            if (r.get("invite_id").asText().equals(inviteId.toString())) {
                return r;
            }
        }
        throw new AssertionError("no invite row for " + inviteId);
    }

    private boolean hasRow(MvcResult res, UUID inviteId) {
        for (JsonNode r : node(res)) {
            if (r.get("invite_id").asText().equals(inviteId.toString())) {
                return true;
            }
        }
        return false;
    }

    /** A non-consumed invite (pending/expired keep consumed_at/by null per the CHECK). Returns the invite id. */
    private UUID seedInvite(String status) {
        UUID inviteId = UUID.randomUUID();
        jdbc.update("INSERT INTO inv_invite (invite_id, email_hash, phone_hash, issued_by, expiry_at, status) "
                        + "VALUES (?, ?, ?, ?, now() + interval '7 days', ?::inv_invite_status)",
                inviteId, "e".getBytes(), "p".getBytes(), issuer, status);
        return inviteId;
    }
}
