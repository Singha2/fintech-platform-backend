package com.arthvritt.platform.adminiam;

import com.arthvritt.platform.shared.error.UnauthorizedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Shared gate for the API-key-authenticated {@code /bootstrap/**} routes — the break-glass surface that
 * authenticates itself with the static {@code platform.bootstrap.api-key} instead of a session bearer. One
 * implementation of the (security-critical) key comparison, reused by every bootstrap controller so the check
 * can't drift between them: constant-time compare, and a blank configured key authenticates no one.
 */
@Component
public class BootstrapApiKeyGuard {

    private static final String BEARER = "Bearer ";

    private final String apiKey;

    public BootstrapApiKeyGuard(@Value("${platform.bootstrap.api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    /** Throws {@link UnauthorizedException} (401) unless the {@code Authorization} header carries the key. */
    public void requireValidKey(String authorization) {
        if (apiKey == null || apiKey.isBlank()) {
            // A blank configured key must never authenticate anyone (e.g. a mis-provisioned prod).
            throw new UnauthorizedException("bootstrap API key is not configured");
        }
        String presented = extractKey(authorization);
        if (presented == null || !constantTimeEquals(presented, apiKey)) {
            throw new UnauthorizedException("invalid or missing bootstrap API key");
        }
    }

    private static String extractKey(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String trimmed = authorization.trim();
        if (trimmed.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return trimmed.substring(BEARER.length()).trim();
        }
        return trimmed; // tolerate a bare key without the "Bearer " prefix
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
