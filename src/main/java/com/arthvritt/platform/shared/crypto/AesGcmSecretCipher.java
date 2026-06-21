package com.arthvritt.platform.shared.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM {@link SecretCipher} for Phase 1. The 256-bit key is supplied by
 * {@code platform.security.secret-key} (base64); the bundled default is <b>dev-only</b> and must be
 * overridden in any real environment. Output layout is {@code [12-byte nonce][ciphertext+16-byte tag]}.
 *
 * <p>This is the in-process stand-in for a KMS — the secret bytes never leave the app un-encrypted,
 * but key management is not production-grade until the KMS adapter replaces this (DL-BE-019).
 */
@Component
public class AesGcmSecretCipher implements SecretCipher {

    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    // Dev-only fallback key (base64 of 32 bytes). Override platform.security.secret-key everywhere real.
    private static final String DEV_KEY_B64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmSecretCipher(@Value("${platform.security.secret-key:" + DEV_KEY_B64 + "}") String keyB64) {
        byte[] keyBytes = Base64.getDecoder().decode(keyB64);
        if (keyBytes.length != 32) {
            throw new IllegalStateException("platform.security.secret-key must decode to 32 bytes (AES-256)");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public byte[] encrypt(byte[] plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ct = cipher.doFinal(plaintext);
            byte[] out = new byte[NONCE_BYTES + ct.length];
            System.arraycopy(nonce, 0, out, 0, NONCE_BYTES);
            System.arraycopy(ct, 0, out, NONCE_BYTES, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("secret encryption failed", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] blob) {
        if (blob == null || blob.length < NONCE_BYTES + (TAG_BITS / 8)) {
            throw new IllegalStateException("ciphertext blob too short to be valid");
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            System.arraycopy(blob, 0, nonce, 0, NONCE_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(blob, NONCE_BYTES, blob.length - NONCE_BYTES);
        } catch (Exception e) {
            throw new IllegalStateException("secret decryption failed", e);
        }
    }
}
