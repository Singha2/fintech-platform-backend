package com.arthvritt.platform.infrastructure.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stamps every request with a correlation id, exposed to logs via the MDC key
 * {@code requestId} (see logback-spring.xml) and echoed back in the
 * {@code X-Request-Id} response header.
 *
 * <p>Honours an inbound {@code X-Request-Id} (e.g. from a gateway) so a single id
 * can span hops — but only if it matches {@link #VALID_REQUEST_ID}; an untrusted value
 * is logged verbatim on every line, so a non-conforming id is discarded and a fresh
 * one generated. This is the thread that lets you grep all log lines for one request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    /** Bounded, no whitespace/control chars — prevents log forging and log-volume abuse. */
    static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String inbound = request.getHeader(REQUEST_ID_HEADER);
        String requestId = (inbound != null && VALID_REQUEST_ID.matcher(inbound).matches())
                ? inbound
                : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
