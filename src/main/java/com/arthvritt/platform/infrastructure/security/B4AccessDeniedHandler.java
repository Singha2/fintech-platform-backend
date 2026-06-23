package com.arthvritt.platform.infrastructure.security;

import com.arthvritt.platform.infrastructure.logging.RequestIdFilter;
import com.arthvritt.platform.infrastructure.web.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * Renders the B4 §4.1 error body for an authorisation denial raised at the security filter chain (an
 * authenticated session hitting a route it may not use). WS-0 has no edge-level role rules yet — authz is
 * enforced at the command boundary — so this is latent; it exists so the "one B4 error body" invariant
 * holds the moment any authorisation rule moves to the edge, rather than leaking Spring's default 403.
 */
class B4AccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper mapper;

    B4AccessDeniedHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(mapper.writeValueAsString(
                ApiError.body("access_denied", "You do not have permission to perform this action.",
                        HttpServletResponse.SC_FORBIDDEN, MDC.get(RequestIdFilter.MDC_KEY))));
    }
}
