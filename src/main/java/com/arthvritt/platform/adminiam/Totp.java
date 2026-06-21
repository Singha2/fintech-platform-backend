package com.arthvritt.platform.adminiam;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * RFC-6238 TOTP (HMAC-SHA1, 6 digits, 30-second step) used for admin MFA (C7 "TOTP preferred",
 * DL-035). Pure functions + a tiny RFC-4648 Base32 encoder for the {@code otpauth://} URI — no
 * external dependency, so the security primitive has no supply-chain surface (DL-BE-019).
 */
public final class Totp {

    static final int STEP_SECONDS = 30;
    static final int DIGITS = 6;
    private static final int SECRET_BYTES = 20; // 160-bit, the SHA-1 block-aligned default
    private static final int[] POW10 = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000};
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private Totp() {
    }

    /** A fresh 160-bit random secret (raw bytes — encrypt before persisting). */
    public static byte[] newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(secret);
        return secret;
    }

    /** The 6-digit code for {@code secret} at instant {@code at}. */
    public static String generate(byte[] secret, Instant at) {
        return generateForCounter(secret, at.getEpochSecond() / STEP_SECONDS);
    }

    /**
     * True if {@code code} matches {@code secret} within ±{@code window} steps of {@code at} — the
     * window absorbs clock skew between the authenticator and the server.
     */
    public static boolean verify(byte[] secret, String code, Instant at, int window) {
        if (code == null || code.length() != DIGITS) {
            return false;
        }
        long base = at.getEpochSecond() / STEP_SECONDS;
        for (long offset = -window; offset <= window; offset++) {
            if (constantTimeEquals(generateForCounter(secret, base + offset), code)) {
                return true;
            }
        }
        return false;
    }

    /** The {@code otpauth://totp/...} provisioning URI an authenticator app scans. */
    public static String otpauthUri(String issuer, String account, byte[] secret) {
        String label = urlEncode(issuer) + ":" + urlEncode(account);
        return "otpauth://totp/" + label + "?secret=" + base32Encode(secret)
                + "&issuer=" + urlEncode(issuer) + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String generateForCounter(byte[] secret, long counter) {
        byte[] msg = new byte[8];
        for (int i = 7; i >= 0; i--) {
            msg[i] = (byte) (counter & 0xff);
            counter >>>= 8;
        }
        byte[] hash = hmacSha1(secret, msg);
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        int otp = binary % POW10[DIGITS];
        return String.format("%0" + DIGITS + "d", otp);
    }

    private static byte[] hmacSha1(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(message);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA1 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                sb.append(BASE32[(buffer >> bits) & 0x1f]);
            }
        }
        if (bits > 0) {
            sb.append(BASE32[(buffer << (5 - bits)) & 0x1f]);
        }
        return sb.toString();
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
