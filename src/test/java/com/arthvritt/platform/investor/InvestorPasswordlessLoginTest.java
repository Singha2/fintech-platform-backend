package com.arthvritt.platform.investor;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-18 Part 1 (M11-B §7) — passwordless investor login. An {@code active}, KYC-approved investor logs in with
 * email → OTP → session (no password, no admin). Enumeration-safe: an unknown email or an ineligible investor
 * gets an indistinguishable {@code {challenge_id}} response and <b>no</b> OTP is sent.
 */
class InvestorPasswordlessLoginTest extends AbstractEdgeHttpTest {

    @BeforeEach
    void clearNotifier() {
        notifier.clear();
    }

    @Test
    void active_investor_logs_in_passwordless_and_session_is_investor_scoped() throws Exception {
        InvestorLogin il = seedActiveInvestorWithLogin(); // active
        String bearer = bearerForInvestorPasswordless(il.login());

        mvc.perform(get("/auth/session").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("investor"))
                .andExpect(jsonPath("$.investor_id").value(il.investorId().toString()))
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.roles").isEmpty());
    }

    @Test
    void request_otp_is_enumeration_safe_for_an_unknown_email() throws Exception {
        MvcResult res = mvc.perform(post("/auth/login/investor/request-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("email", "nobody-" + UUID.randomUUID() + "@nowhere.test"))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(node(res).hasNonNull("challenge_id")).isTrue();
    }

    @Test
    void an_ineligible_investor_gets_a_challenge_shape_but_no_otp() throws Exception {
        // A mid-onboarding (kyc_approved, not active) investor must not obtain a real OTP — but the response is
        // shape-identical to the happy path (DoR-1/2/3: gate on current inv_account.status = 'active').
        InvestorLogin il = seedActiveInvestorWithLogin("kyc_approved");

        MvcResult res = mvc.perform(post("/auth/login/investor/request-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", il.login().email()))))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(node(res).hasNonNull("challenge_id")).isTrue();
        assertThat(notifier.lastCodeFor(il.login().identityId())).isEmpty(); // nothing was actually sent
    }
}
