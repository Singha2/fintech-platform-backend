package com.arthvritt.platform.infrastructure.web;

import com.arthvritt.platform.infrastructure.logging.RequestIdFilter;
import com.arthvritt.platform.shared.error.PlatformException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;

/**
 * Single mapping of exceptions to HTTP responses (RFC 7807 {@link ProblemDetail}).
 *
 * <p>Two jobs: (1) log every exception once, with stack trace and the request
 * correlation id, so debugging is a single grep; (2) return a clean problem
 * document to the client — never a raw stack trace.
 *
 * <p>Domain errors ({@link PlatformException} → ValidationException / … each with a stable
 * {@code errorCode}) map to their declared status. Spring Security denials thrown from inside
 * a handler (e.g. method security) are mapped to 403/401 here — without this they would be
 * caught by the catch-all and reported as 500. Everything else is a 500. Extends
 * {@link ResponseEntityExceptionHandler} so Spring's own MVC exceptions (validation,
 * unreadable body, 404, …) flow through the same advice.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(PlatformException.class)
    public ProblemDetail handleDomain(PlatformException ex) {
        // A domain error is expected business flow, not a defect -> WARN, no stack trace.
        log.warn("Domain exception [code={}] [req={}]: {}",
                ex.getErrorCode(), MDC.get(RequestIdFilter.MDC_KEY), ex.getMessage());
        return problem(ex.getStatus(), ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        // Authorization denial -> WARN; don't leak the internal message to the client.
        log.warn("Access denied [req={}]: {}", MDC.get(RequestIdFilter.MDC_KEY), ex.getMessage());
        return problem(HttpStatus.FORBIDDEN, "access_denied",
                "You do not have permission to perform this action.");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex) {
        log.warn("Authentication failure [req={}]: {}", MDC.get(RequestIdFilter.MDC_KEY), ex.getMessage());
        return problem(HttpStatus.UNAUTHORIZED, "unauthenticated",
                "Authentication is required or has failed.");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUncaught(Exception ex) {
        // ERROR with the throwable -> full stack trace; requestId is already on the line via MDC.
        log.error("Unhandled exception [req={}]", MDC.get(RequestIdFilter.MDC_KEY), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal",
                "An unexpected error occurred. Quote the request id when reporting this.");
    }

    /** Builds a ProblemDetail with the stable {@code errorCode} and the request correlation id. */
    private ProblemDetail problem(HttpStatus status, String errorCode, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(status.getReasonPhrase());
        problem.setDetail(detail);
        problem.setType(URI.create("urn:platform:error:" + errorCode));
        problem.setProperty("errorCode", errorCode);
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
        return problem;
    }
}
