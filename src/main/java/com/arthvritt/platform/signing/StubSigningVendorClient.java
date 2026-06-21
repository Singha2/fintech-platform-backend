package com.arthvritt.platform.signing;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Phase-1 fake e-Sign vendor (DL-048, G14). A deterministic hosted session URL on initiate and an
 * <b>auto-success</b> fake certificate serial on completion — the "signed via stub" the Walking
 * Skeleton's assignment step uses. The real adapter is a drop-in replacement at the Production gate.
 */
@Component
public class StubSigningVendorClient implements SigningVendorClient {

    @Override
    public InitiateAck initiate(UUID vsrId, byte[] docHash, String signerRef, SignMethod signMethod) {
        return new InitiateAck("https://stub-sign.local/session/" + vsrId);
    }

    @Override
    public CompletionAck complete(UUID vsrId) {
        // Full vsr id (not a 12-char prefix) so the fake cert serial is collision-free even for
        // near-simultaneous UUIDv7s that share a timestamp prefix.
        return new CompletionAck("STUBCERT" + vsrId.toString().replace("-", "").toUpperCase());
    }
}
