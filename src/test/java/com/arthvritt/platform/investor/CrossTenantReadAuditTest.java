package com.arthvritt.platform.investor;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-18 Part 3 (M11-B §7) — denied cross-tenant reads are audited. A mismatched-id portfolio read still returns
 * {@code 403 cross_tenant_read} (M10-D), and now also emits an {@code investor.CrossTenantReadDenied} telemetry
 * event so tenant-isolation probes are visible. A successful own read writes no such event (reads stay unaudited).
 */
class CrossTenantReadAuditTest extends AbstractEdgeHttpTest {

    @Test
    void a_denied_cross_tenant_portfolio_read_is_audited() throws Exception {
        InvestorLogin a = seedActiveInvestorWithLogin();
        InvestorLogin b = seedActiveInvestorWithLogin();
        String bearerA = bearerFor(a.login());

        mvc.perform(get("/investors/{id}/subscriptions", b.investorId())
                        .header("Authorization", "Bearer " + bearerA))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("cross_tenant_read"));

        Integer denials = jdbc.queryForObject("SELECT count(*) FROM sys_audit_event "
                        + "WHERE event_type = 'investor.CrossTenantReadDenied' AND actor->>'actor_id' = ? "
                        + "AND payload->>'attempted_investor_id' = ?",
                Integer.class, a.login().identityId().toString(), b.investorId().toString());
        assertThat(denials).isEqualTo(1);
    }

    @Test
    void an_own_portfolio_read_writes_no_denial_audit() throws Exception {
        InvestorLogin a = seedActiveInvestorWithLogin();
        String bearerA = bearerFor(a.login());

        mvc.perform(get("/investors/{id}/subscriptions", a.investorId())
                        .header("Authorization", "Bearer " + bearerA))
                .andExpect(status().isOk());

        Integer denials = jdbc.queryForObject("SELECT count(*) FROM sys_audit_event "
                        + "WHERE event_type = 'investor.CrossTenantReadDenied' AND actor->>'actor_id' = ?",
                Integer.class, a.login().identityId().toString());
        assertThat(denials).isZero();
    }
}
