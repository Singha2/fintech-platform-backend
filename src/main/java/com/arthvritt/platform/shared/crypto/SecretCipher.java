package com.arthvritt.platform.shared.crypto;

/**
 * App-layer encryption for at-rest secrets (e.g. {@code auth_mfa_factor.secret_encrypted}, the TOTP
 * shared secret — Spec §7.3, M4b). A real interface; the Phase-1 adapter is an in-process AES-GCM
 * cipher with a config-supplied key ({@link AesGcmSecretCipher}). A KMS-backed adapter swaps in at
 * Production with no caller change (the same ACL-port discipline as BC15).
 */
public interface SecretCipher {

    /** Encrypts plaintext to an opaque, self-describing blob (carries its own nonce). */
    byte[] encrypt(byte[] plaintext);

    /** Reverses {@link #encrypt}; throws if the blob is corrupt or fails authentication. */
    byte[] decrypt(byte[] ciphertext);
}
