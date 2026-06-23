package com.arthvritt.platform.infrastructure.web;

import com.arthvritt.platform.shared.error.ValidationException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared edge helpers for reading + validating JSON command bodies into typed values, and for minting a
 * creating-command's aggregate id. Used uniformly by the command controllers (admin, supplier, buyer, …)
 * so input validation and idempotent-create derivation stay identical across bounded contexts — a missing
 * or mistyped field is a clean 400 (B4), never an NPE/500 or a silent coercion.
 */
public final class RequestBodies {

    private RequestBodies() {
    }

    /** A required non-blank string field. */
    public static String requiredString(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new ValidationException("missing required field: " + field);
        }
        return value.toString();
    }

    /** A required non-empty array of non-blank strings (each element validated, no unchecked cast). */
    public static List<String> requiredStrings(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new ValidationException("missing required array field: " + field);
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof String s) || s.isBlank()) {
                throw new ValidationException("field '" + field + "' must be an array of non-blank strings");
            }
            result.add(s);
        }
        return result;
    }

    /** A required money amount in integer paise (≥ 0) — a fractional value is rejected, never truncated. */
    public static long requiredPaise(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (!(value instanceof Number number)) {
            throw new ValidationException("missing required numeric field: " + field);
        }
        if (number.longValue() != number.doubleValue()) {
            throw new ValidationException("field '" + field + "' must be an integer amount in paise");
        }
        return number.longValue();
    }

    /** A required strictly-positive paise amount (for {@code positive_money_paise} fields) — rejects ≤ 0
     *  at the edge rather than letting the DB CHECK surface it as a 500. */
    public static long requiredPositivePaise(Map<String, Object> body, String field) {
        long paise = requiredPaise(body, field);
        if (paise <= 0) {
            throw new ValidationException("field '" + field + "' must be a positive amount in paise");
        }
        return paise;
    }

    /**
     * Deterministic aggregate id for a creating command, derived from {@code (prefix, command_id, payload)}.
     * A same-command_id replay with the same body resolves to the same id (the gateway replays it); any
     * divergent field yields a different id → the gateway 409s the conflict.
     */
    public static UUID deriveAggregateId(String prefix, UUID commandId, String payload) {
        return UUID.nameUUIDFromBytes((prefix + ":" + commandId + ":" + payload).getBytes(StandardCharsets.UTF_8));
    }
}
