package com.arthvritt.platform.adminiam;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-algorithm tests for {@link Totp} — no Spring/DB. The RFC-6238 Appendix-B SHA-1 vectors prove
 * the implementation is correct independently of how it is wired, and the Base32 vector pins the
 * otpauth-secret encoding (RFC 4648). 6-digit codes are the low 6 of the RFC's 8-digit values.
 */
class TotpTest {

    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void rfc6238_sha1_vectors() {
        assertThat(Totp.generate(RFC_SECRET, Instant.ofEpochSecond(59))).isEqualTo("287082");
        assertThat(Totp.generate(RFC_SECRET, Instant.ofEpochSecond(1111111109))).isEqualTo("081804");
        assertThat(Totp.generate(RFC_SECRET, Instant.ofEpochSecond(1234567890))).isEqualTo("005924");
    }

    @Test
    void verify_accepts_within_skew_window_and_rejects_outside() {
        byte[] secret = Totp.newSecret();
        Instant t = Instant.ofEpochSecond(1_000_000);
        String code = Totp.generate(secret, t);

        assertThat(Totp.verify(secret, code, t, 1)).isTrue();
        assertThat(Totp.verify(secret, code, t.plusSeconds(30), 1)).isTrue();   // +1 step, inside window
        assertThat(Totp.verify(secret, code, t.plusSeconds(120), 1)).isFalse(); // +4 steps, outside
        assertThat(Totp.verify(secret, "000000", t, 1)).isFalse();
    }

    @Test
    void base32_rfc4648_vector() {
        assertThat(Totp.base32Encode("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
    }
}
