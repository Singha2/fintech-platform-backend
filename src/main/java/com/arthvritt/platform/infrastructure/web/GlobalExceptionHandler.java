package com.arthvritt.platform.infrastructure.web;

import com.arthvritt.platform.infrastructure.logging.RequestIdFilter;
import com.arthvritt.platform.shared.error.PlatformException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;

/**
 * Single mapping of exceptions to HTTP responses — the B4 §4.1 canonical error body (one flat, snake_case
 * shape via {@link ApiError}, so screens and AI agents dispatch on {@code error_code} / {@code error_category}).
 *
 * <p>Two jobs: (1) log every exception once, with stack trace and the request correlation id, so debugging
 * is a single grep; (2) return a clean error body to the client — never a raw stack trace.
 *
 * <p>Domain errors ({@link PlatformException} → {@code CommandRejectedException} / ValidationException / …,
 * each with a stable {@code errorCode}) map to their declared status and B4 category. Spring Security
 * denials thrown from inside a handler are mapped to 403/401 here — without this they would be caught by
 * the catch-all and reported as 500. Everything else is a 500. (The unauthenticated-at-the-edge 401 is
 * rendered earlier by the security {@code AuthenticationEntryPoint}, not here.) Extends
 * {@link ResponseEntityExceptionHandler} so Spring's own MVC exceptions flow through the same advice.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<Object> handleDomain(PlatformException ex) {
        // A domain error is expected business flow, not a defect -> WARN, no stack trace.
        log.warn("Domain exception [code={}] [req={}]: {}",
                ex.getErrorCode(), MDC.get(RequestIdFilter.MDC_KEY), ex.getMessage());
        return body(ex.getStatus(), ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex) {
        // Authorization denial -> WARN; don't leak the internal message to the client.
        log.warn("Access denied [req={}]: {}", MDC.get(RequestIdFilter.MDC_KEY), ex.getMessage());
        return body(HttpStatus.FORBIDDEN, "access_denied",
                "You do not have permission to perform this action.");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Object> handleAuthentication(AuthenticationException ex) {
        log.warn("Authentication failure [req={}]: {}", MDC.get(RequestIdFilter.MDC_KEY), ex.getMessage());
        return body(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication is required or has failed.");
    }

    /** A malformed path/header value (e.g. a non-UUID {@code X-Command-Id}) → 400, not 500. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Type mismatch [param={}] [req={}]: {}", ex.getName(), MDC.get(RequestIdFilter.MDC_KEY),
                ex.getMessage());
        return body(HttpStatus.BAD_REQUEST, "bad_request", "Request parameter '" + ex.getName() + "' is malformed.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUncaught(Exception ex) {
        // ERROR with the throwable -> full stack trace; requestId is already on the line via MDC.
        log.error("Unhandled exception [req={}]", MDC.get(RequestIdFilter.MDC_KEY), ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal",
                "An unexpected error occurred. Quote the request id when reporting this.");
    }

    /**
     * Re-renders every Spring MVC framework exception (missing required header, unreadable body,
     * unsupported method/media-type, no handler …) through the B4 error body — otherwise
     * {@link ResponseEntityExceptionHandler} would emit its own RFC-7807 ProblemDetail, leaking a
     * non-B4 shape on the most common 4xx paths (e.g. a missing {@code X-Command-Id}).
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        log.warn("Request exception [{}] [req={}]: {}", statusCode, MDC.get(RequestIdFilter.MDC_KEY),
                ex.getMessage());
        String errorCode = ex instanceof MissingRequestHeaderException ? "missing_header"
                : statusCode.is4xxClientError() ? "bad_request" : "internal";
        String message = statusCode.is4xxClientError() ? ex.getMessage() : "The request could not be processed.";
        Map<String, Object> errorBody = ApiError.body(errorCode, message, statusCode.value(),
                MDC.get(RequestIdFilter.MDC_KEY));
        return new ResponseEntity<>(errorBody, headers, statusCode);
    }

    /** Builds the B4 §4.1 error body with the stable {@code error_code}, category, and correlation id. */
    private ResponseEntity<Object> body(HttpStatus status, String errorCode, String message) {
        Map<String, Object> error = ApiError.body(errorCode, message, status.value(),
                MDC.get(RequestIdFilter.MDC_KEY));
        return ResponseEntity.status(status).body(error);
    }
}
