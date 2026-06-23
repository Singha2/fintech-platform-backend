package com.arthvritt.platform.infrastructure.web;

import com.arthvritt.platform.infrastructure.logging.RequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * M1a / WS-0: an unhandled exception is mapped to the B4 §4.1 error body (no stack trace leaks) and
 * carries the request correlation id, which is also echoed in the response header (see §7).
 * Standalone setup — no Spring context / Docker needed; exercises filter + advice together.
 */
class GlobalExceptionHandlerTest {

    @RestController
    static class BoomController {
        @GetMapping("/boom")
        String boom() {
            throw new RuntimeException("kaboom");
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new BoomController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void uncaught_exception_becomes_b4_error_body_with_correlation_id() throws Exception {
        mockMvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(RequestIdFilter.REQUEST_ID_HEADER, not(blankOrNullString())))
                .andExpect(jsonPath("$.error_code").value("internal"))
                .andExpect(jsonPath("$.error_category").value("internal"))
                .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void inbound_request_id_is_propagated_as_correlation_id() throws Exception {
        mockMvc.perform(get("/boom").header(RequestIdFilter.REQUEST_ID_HEADER, "trace-123"))
                .andExpect(header().string(RequestIdFilter.REQUEST_ID_HEADER, "trace-123"))
                .andExpect(jsonPath("$.correlation_id").value("trace-123"));
    }

    @Test
    void malicious_inbound_request_id_is_discarded() throws Exception {
        String forged = "real ERROR forged-log-line";
        mockMvc.perform(get("/boom").header(RequestIdFilter.REQUEST_ID_HEADER, forged))
                .andExpect(header().string(RequestIdFilter.REQUEST_ID_HEADER, not(forged)))
                .andExpect(jsonPath("$.correlation_id").value(not(forged)));
    }
}
