package com.arthvritt.platform.signing;

import java.util.UUID;

/**
 * BC19 Signing anti-corruption port — the e-Sign operations BC5 Assignment &amp; Signing (M12) calls.
 * {@code initiateSignature} starts a vendor session (returns the hosted URL); {@code completeSignature}
 * is the await/webhook entry (the stub completes in-process); {@code fetchSignature} reads the result.
 * Keyed by the caller-supplied {@code vsrId} + the {@code (signatureRequestId, docHash)} idempotency
 * pair (VS.1). No vendor model crosses the port (A1/B1).
 */
public interface SigningPort {

    SignatureSession initiateSignature(UUID vsrId, UUID signatureRequestId, byte[] docHash,
                                       String signerRef, SignMethod signMethod);

    SignatureResult completeSignature(UUID vsrId);

    SignatureResult fetchSignature(UUID vsrId);

    record SignatureSession(UUID vsrId, String vendorSessionUrl) {
    }

    record SignatureResult(UUID vsrId, SignatureSessionStatus status, String certSerial) {
    }
}
