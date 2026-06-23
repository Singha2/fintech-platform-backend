package com.arthvritt.platform.infrastructure.web;

import com.arthvritt.platform.shared.error.ValidationException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Shared edge helpers for reading + validating JSON command bodies into typed values, and for minting a
 * creating-command's aggregate id. Used uniformly by the command controllers (admin, supplier, buyer, …)
 * so input validation and idempotent-create derivation stay identical across bounded contexts — a missing
 * or mistyped field is a clean 400 (B4), never an NPE/500 or a silent coercion.
 */
public final class RequestBodies {

    // Format-validate identity fields at the edge so an operator typo is a clean 400, not a 500 from the
    // DB domain CHECK (the schema's last line of defence). Patterns match the V1 domains exactly.
    private static final Pattern PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    private static final Pattern GSTIN =
            Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$");
    private static final Pattern FOUR_DIGITS = Pattern.compile("^[0-9]{4}$");

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

    /** A required PAN (`pan_type`: 5 letters + 4 digits + 1 letter). */
    public static String requiredPan(Map<String, Object> body, String field) {
        return requiredMatching(body, field, PAN, "a valid PAN");
    }

    /** A required GSTIN (`gstin_type`: the 15-char GSTIN format). */
    public static String requiredGstin(Map<String, Object> body, String field) {
        return requiredMatching(body, field, GSTIN, "a valid GSTIN");
    }

    /** A required 4-digit string (Aadhaar last-4, bank account last-4 — `CHAR(4)` / digit CHECK). */
    public static String requiredFourDigits(Map<String, Object> body, String field) {
        return requiredMatching(body, field, FOUR_DIGITS, "exactly 4 digits");
    }

    private static String requiredMatching(Map<String, Object> body, String field, Pattern pattern, String shape) {
        String value = requiredString(body, field);
        if (!pattern.matcher(value).matches()) {
            throw new ValidationException("field '" + field + "' must be " + shape);
        }
        return value;
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
