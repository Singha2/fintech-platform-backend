package com.arthvritt.platform.infrastructure.security;

import com.arthvritt.platform.infrastructure.logging.RequestIdFilter;
import com.arthvritt.platform.infrastructure.web.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Renders the B4 §4.1 error body for a pre-authorisation 401 (no/invalid/expired bearer on a protected
 * route). These failures emit <b>no audit envelope</b> (B2 §5.6, G22) — the actor was never authorised to
 * record a business fact. The specific code is whatever {@link SessionBearerAuthFilter} stashed
 * ({@code bearer_invalid} / {@code bearer_expired}), defaulting to {@code bearer_missing}.
 */
class BearerAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper mapper;

    BearerAuthenticationEntryPoint(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Object stashed = request.getAttribute(SessionBearerAuthFilter.AUTH_ERROR_ATTR);
        String errorCode = stashed instanceof String code ? code : "bearer_missing";
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(mapper.writeValueAsString(
                ApiError.body(errorCode, "authentication is required", HttpServletResponse.SC_UNAUTHORIZED,
                        MDC.get(RequestIdFilter.MDC_KEY))));
    }
}
