package com.arthvritt.platform.infrastructure.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Verifies the HMAC-SHA256 signature on an inbound vendor webhook (B4 §5.1, C10, A2 §1.2). The signed
 * payload is {@code "<timestamp>.<rawBody>"}; the signature is lowercase hex. Verification is done over the
 * <b>exact received bytes</b> (the controller passes the raw body String, parsed only after this passes),
 * and the comparison is constant-time. A timestamp outside the {@link #REPLAY_WINDOW} is rejected as a
 * replay (A2 §1.2), even if the signature is otherwise valid.
 */
@Component
public class HmacVerifier {

    /** Reject any signed timestamp more than this far from server time (A2 §1.2). */
    static final Duration REPLAY_WINDOW = Duration.ofMinutes(5);

    /**
     * Throws {@link WebhookSignatureException} (→ 401) if the signature does not match or the timestamp is
     * stale. The caller must emit the {@code WebhookSignature.Invalid} envelope before propagating.
     */
    public void verify(String timestampHeader, String signatureHeader, String rawBody, String secret) {
        long timestamp = parseTimestamp(timestampHeader);
        if (Duration.between(Instant.ofEpochMilli(timestamp), Instant.now()).abs().compareTo(REPLAY_WINDOW) > 0) {
            throw new WebhookSignatureException("webhook timestamp is outside the replay window");
        }
        byte[] expected = hmac(secret, timestampHeader + "." + rawBody);
        byte[] provided = decodeHex(signatureHeader);
        // Constant-time compare; MessageDigest.isEqual is length-aware and non-short-circuiting.
        if (!MessageDigest.isEqual(expected, provided)) {
            throw new WebhookSignatureException("webhook signature does not match");
        }
    }

    private static long parseTimestamp(String header) {
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException e) {
            throw new WebhookSignatureException("webhook timestamp is missing or malformed");
        }
    }

    private static byte[] hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static byte[] decodeHex(String hex) {
        if (hex == null) {
            throw new WebhookSignatureException("webhook signature is missing");
        }
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            throw new WebhookSignatureException("webhook signature is not valid hex");
        }
    }
}
