package com.arthvritt.platform.buyer;

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
 * BE-15 Part 1 (M11-C §7) — passwordless ack-user login. An active {@code acknowledgment_user} of an active buyer
 * logs in with email + OTP only (no password, AU.1) → a {@code kind='acknowledgment_user'} session carrying its
 * {@code buyer_id}. Enumeration-safe: an unknown/ineligible email gets an indistinguishable response, no OTP.
 */
class AckUserPasswordlessLoginTest extends AbstractEdgeHttpTest {

    @BeforeEach
    void clearNotifier() {
        notifier.clear();
    }

    @Test
    void active_ack_user_logs_in_and_session_is_buyer_scoped() {
        AckUserLogin ack = seedActiveAckUserWithLogin();
        String bearer = bearerForAckUserPasswordless(ack);

        try {
            mvc.perform(get("/auth/session").header("Authorization", "Bearer " + bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.kind").value("acknowledgment_user"))
                    .andExpect(jsonPath("$.buyer_id").value(ack.buyerId().toString()))
                    .andExpect(jsonPath("$.roles").isArray())
                    .andExpect(jsonPath("$.roles").isEmpty());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void request_otp_is_enumeration_safe_for_an_unknown_email() throws Exception {
        MvcResult res = mvc.perform(post("/auth/login/ack-user/request-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("email", "nobody-" + UUID.randomUUID() + "@nowhere.test"))))
                .andExpect(status().isOk()).andReturn();
        assertThat(node(res).hasNonNull("challenge_id")).isTrue();
    }

    @Test
    void an_inactive_ack_user_gets_a_challenge_shape_but_no_otp() throws Exception {
        AckUserLogin ack = seedActiveAckUserWithLogin("active", false, "active"); // ack is_active = false
        MvcResult res = mvc.perform(post("/auth/login/ack-user/request-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", ack.email()))))
                .andExpect(status().isOk()).andReturn();
        assertThat(node(res).hasNonNull("challenge_id")).isTrue();
        assertThat(notifier.lastCodeFor(ack.identityId())).isEmpty();
    }

    @Test
    void an_ack_user_of_a_suspended_buyer_gets_no_otp() throws Exception {
        AckUserLogin ack = seedActiveAckUserWithLogin("active", true, "suspended"); // buyer not active
        mvc.perform(post("/auth/login/ack-user/request-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", ack.email()))))
                .andExpect(status().isOk());
        assertThat(notifier.lastCodeFor(ack.identityId())).isEmpty();
    }
}
