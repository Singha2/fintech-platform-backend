package com.arthvritt.platform.credit;

import com.arthvritt.platform.auth.ActionSensitivity;
import com.arthvritt.platform.auth.AuthSession;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.infrastructure.web.CommandResponse;
import com.arthvritt.platform.infrastructure.web.CommandResponseAssembler;
import com.arthvritt.platform.infrastructure.web.RequestBodies;
import com.arthvritt.platform.shared.error.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * BC3 credit HTTP surface (M6). Credit Reviewer policy commands: set a pricing band (M6-A) and the
 * buyer/supplier credit profiles (M6-B). Thin adapters over {@link CreditService}/{@code CommandGateway}.
 */
@RestController
@RequestMapping("/credit")
public class CreditController {

    private static final String CONTEXT = "credit";

    private final CreditService credit;
    private final CommandResponseAssembler responses;

    public CreditController(CreditService credit, CommandResponseAssembler responses) {
        this.credit = credit;
        this.responses = responses;
    }

    @PostMapping("/pricing-bands")
    public ResponseEntity<CommandResponse> setPricingBand(@AuthenticationPrincipal AuthSession session,
                                                          @RequestHeader("X-Command-Id") UUID commandId,
                                                          @RequestBody Map<String, Object> body) {
        UUID buyerId = uuid(RequestBodies.requiredString(body, "buyer_id"));
        String tenorBucket = RequestBodies.requiredString(body, "tenor_bucket");
        int minBps = RequestBodies.requiredPositiveInt(body, "rate_range_min_bps");
        int maxBps = RequestBodies.requiredPositiveInt(body, "rate_range_max_bps");
        int feeBps = nonNegativeInt(body, "fee_bps");
        String effectiveFrom = optionalIsoDate(body, "effective_from");
        UUID bandId = RequestBodies.deriveAggregateId("pricing-band", commandId,
                String.join(":", buyerId.toString(), tenorBucket, String.valueOf(minBps), String.valueOf(maxBps),
                        String.valueOf(feeBps)));
        CommandRequest request = command(session, commandId, bandId, ".PricingBand.Set", "PricingBand");
        CommandResult<UUID> result = credit.setPricingBand(request, buyerId, tenorBucket, minBps, maxBps, feeBps,
                effectiveFrom);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(responses.from(result));
    }

    @PostMapping("/buyers/{id}/profile")
    public CommandResponse setBuyerCreditProfile(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                                 @RequestHeader("X-Command-Id") UUID commandId,
                                                 @RequestBody Map<String, Object> body) {
        String sector = RequestBodies.requiredString(body, "sector");
        String ratingSource = RequestBodies.requiredString(body, "rating_source");
        String rating = RequestBodies.requiredString(body, "rating");
        long creditLimitPaise = RequestBodies.requiredPositivePaise(body, "credit_limit_paise");
        int tenorCapDays = RequestBodies.requiredPositiveInt(body, "tenor_cap_days");
        CommandRequest request = command(session, commandId, id, ".BuyerCreditProfile.Set", "BuyerCreditProfile");
        return responses.from(credit.setBuyerCreditProfile(request, id, sector, ratingSource, rating,
                creditLimitPaise, tenorCapDays));
    }

    @PostMapping("/suppliers/{id}/profile")
    public CommandResponse setSupplierCreditProfile(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                                    @RequestHeader("X-Command-Id") UUID commandId,
                                                    @RequestBody Map<String, Object> body) {
        String riskRating = RequestBodies.requiredString(body, "risk_rating");
        long exposureCapPaise = nonNegativeLong(body, "exposure_cap_paise");
        CommandRequest request = command(session, commandId, id, ".SupplierCreditProfile.Set",
                "SupplierCreditProfile");
        return responses.from(credit.setSupplierCreditProfile(request, id, riskRating, exposureCapPaise));
    }

    private CommandRequest command(AuthSession session, UUID commandId, UUID aggregateId, String name,
                                   String aggregateType) {
        return new CommandRequest(session, commandId, CONTEXT, CONTEXT + name, aggregateType, aggregateId,
                0, "admin_user", ActionSensitivity.SENSITIVE);
    }

    /** A required non-negative paise amount (exposure_cap may legitimately be 0). */
    private static long nonNegativeLong(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        // longValue() != doubleValue() rejects non-integral and BigInteger overflow (the money-safety check).
        if (!(value instanceof Number n) || n.longValue() != n.doubleValue()) {
            throw new ValidationException("field '" + field + "' must be a non-negative integer (paise)");
        }
        long v = n.longValue();
        if (v < 0) {
            throw new ValidationException("field '" + field + "' must be >= 0");
        }
        return v;
    }

    /** A required non-negative integer (fee_bps may legitimately be 0, so it is not {@code requiredPositiveInt}). */
    private static int nonNegativeInt(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        // longValue() != doubleValue() rejects both non-integral (1.5) and overflow (BigInteger > Long.MAX,
        // whose longValue() would silently keep only the low 64 bits) — the codebase money-safety check.
        if (!(value instanceof Number n) || n.longValue() != n.doubleValue()) {
            throw new ValidationException("field '" + field + "' must be a non-negative integer");
        }
        long v = n.longValue();
        if (v < 0 || v > Integer.MAX_VALUE) {
            throw new ValidationException("field '" + field + "' is out of range");
        }
        return (int) v;
    }

    /** An optional ISO-8601 date string; validated here so a malformed date is a clean 400, not a DB 500. */
    private static String optionalIsoDate(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw new ValidationException("field '" + field + "' must be an ISO-8601 date string (yyyy-MM-dd)");
        }
        if (s.isBlank()) {
            return null;
        }
        try {
            java.time.LocalDate.parse(s);
        } catch (java.time.format.DateTimeParseException e) {
            throw new ValidationException("field '" + field + "' must be an ISO-8601 date (yyyy-MM-dd)");
        }
        return s;
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("buyer_id is not a valid id");
        }
    }
}
