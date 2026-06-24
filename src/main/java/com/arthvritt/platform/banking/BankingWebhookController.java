package com.arthvritt.platform.banking;

import com.arthvritt.platform.banking.EscrowPort.WebhookOutcome;
import com.arthvritt.platform.infrastructure.security.HmacVerifier;
import com.arthvritt.platform.infrastructure.security.WebhookSignatureException;
import com.arthvritt.platform.settlement.SettlementService;
import com.arthvritt.platform.shared.error.ValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * BC18 inbound banking-webhook ingress (WS-5, B4 §5). The vendor is authenticated by HMAC, not a bearer
 * (this route is permitted in {@code SecurityConfig}). Order (C10): verify the signature over the <b>raw
 * bytes</b> BEFORE any parse or DB read; on failure, record {@code WebhookSignature.Invalid} and return 401.
 * On success, hand the inflow to the {@link EscrowAclService} (dedup on {@code vendor_event_id}/{@code utr},
 * audit); only a freshly-accepted inflow is reconciled by {@link SettlementService} (so a re-delivery is
 * counted once). Returns 200 even on a duplicate (B4 §5.2) so the vendor stops re-delivering.
 */
@RestController
public class BankingWebhookController {

    private final HmacVerifier hmac;
    private final EscrowAclService escrow;
    private final SettlementService settlement;
    private final ObjectMapper mapper;
    private final String secret;

    public BankingWebhookController(HmacVerifier hmac, EscrowAclService escrow, SettlementService settlement,
                                    ObjectMapper mapper,
                                    @Value("${platform.webhook.banking.secret}") String secret) {
        this.hmac = hmac;
        this.escrow = escrow;
        this.settlement = settlement;
        this.mapper = mapper;
        this.secret = secret;
    }

    @PostMapping("/webhooks/banking/{vendor}/inflow.received")
    public Map<String, String> inflow(@PathVariable String vendor,
                                      @RequestHeader(value = "X-Timestamp", required = false) String timestamp,
                                      @RequestHeader(value = "X-Signature", required = false) String signature,
                                      @RequestBody(required = false) String rawBody) {
        String body = rawBody == null ? "" : rawBody;
        try {
            hmac.verify(timestamp, signature, body, secret); // BEFORE any parse/read (C10)
        } catch (WebhookSignatureException e) {
            // Security-relevant fact: record it (its own tx) then surface the 401.
            escrow.recordSignatureInvalid(vendor, body.getBytes(StandardCharsets.UTF_8));
            throw e;
        }

        Inflow in = parse(body);
        WebhookOutcome outcome = escrow.processInflowWebhook(in.vaId(), in.amountPaise(), in.utr(), in.eventId());
        if (outcome == WebhookOutcome.APPLIED) {
            settlement.recordReconciledInflow(in.vaId(), in.amountPaise(), in.utr());
        }
        return Map.of("outcome", outcome.name().toLowerCase());
    }

    private Inflow parse(String body) {
        try {
            Map<?, ?> json = mapper.readValue(body, Map.class);
            UUID vaId = UUID.fromString(String.valueOf(json.get("va_id")));
            // Money is integer paise — reject a float-derived amount rather than truncate it (never longValue() a Double).
            if (!(json.get("amount_paise") instanceof Number n) || n.longValue() != n.doubleValue()) {
                throw new ValidationException("amount_paise must be an integer");
            }
            long amount = n.longValue();
            String utr = String.valueOf(json.get("utr"));
            String eventId = String.valueOf(json.get("event_id"));
            if (utr.isBlank() || "null".equals(utr) || eventId.isBlank() || "null".equals(eventId)) {
                throw new ValidationException("webhook body is missing utr/event_id");
            }
            return new Inflow(vaId, amount, utr, eventId);
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new ValidationException("malformed inflow webhook body");
        }
    }

    private record Inflow(UUID vaId, long amountPaise, String utr, String eventId) {
    }
}
