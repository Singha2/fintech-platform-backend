package com.arthvritt.platform.infrastructure.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The single builder of the B4 §4.1 canonical error body — one flat, snake_case JSON shape used for every
 * non-success response, by both {@link GlobalExceptionHandler} (domain rejects reaching a controller) and
 * the security {@code AuthenticationEntryPoint} (pre-authorisation 401s). Screens and AI agents dispatch on
 * {@code error_code} + {@code error_category}, so the mapping from our stable error codes to the nine B4
 * categories lives here, once.
 */
public final class ApiError {

    /** Stable error_code → B4 §4.2 category. Unknown codes fall back to {@code internal}. */
    private static final Map<String, String> CATEGORY = Map.ofEntries(
            Map.entry("bearer_missing", "auth_failure"),
            Map.entry("bearer_invalid", "auth_failure"),
            Map.entry("bearer_expired", "auth_failure"),
            Map.entry("bad_credentials", "auth_failure"),
            Map.entry("unauthenticated", "auth_failure"),
            Map.entry("mfa_assertion_missing", "mfa_missing_or_expired"),
            Map.entry("mfa_assertion_expired", "mfa_missing_or_expired"),
            Map.entry("role_not_held", "role_not_authorised"),
            Map.entry("sod_role_block", "role_not_authorised"),
            Map.entry("access_denied", "role_not_authorised"),
            Map.entry("aggregate_version_stale", "version_conflict"),
            Map.entry("command_id_payload_mismatch", "idempotency_conflict"),
            Map.entry("command_in_progress", "idempotency_conflict"),
            Map.entry("validation_failed", "invariant_violation"),
            // Transport-level 400s/404s sit below B4's nine domain categories (a missing header / malformed
            // body / unknown resource is not a domain reject) — they emit no envelope (B4 §2.2).
            Map.entry("bad_request", "bad_request"),
            Map.entry("missing_header", "bad_request"),
            Map.entry("not_found", "not_found"));

    /** Codes a naive client retry can clear (B4 §4.1 {@code retryable}). */
    private static final Set<String> RETRYABLE = Set.of("aggregate_version_stale", "command_in_progress");

    private ApiError() {
    }

    public static String categoryFor(String errorCode) {
        return CATEGORY.getOrDefault(errorCode, "internal");
    }

    /**
     * The flat B4 error body. {@code violating_rule} / {@code violating_invariant_id} stay null for WS-0's
     * no-envelope rejects (the 422 / maker-checker variant that fills them lands at WS-4, reusing this shape).
     */
    public static Map<String, Object> body(String errorCode, String message, int status, String correlationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error_code", errorCode);
        body.put("error_category", categoryFor(errorCode));
        body.put("violating_rule", null);
        body.put("violating_invariant_id", null);
        body.put("message", message);
        body.put("status", status);
        body.put("correlation_id", correlationId);
        body.put("retryable", RETRYABLE.contains(errorCode));
        return body;
    }
}
