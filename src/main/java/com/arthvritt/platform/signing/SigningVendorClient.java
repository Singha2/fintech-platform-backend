package com.arthvritt.platform.signing;

import java.util.UUID;

/**
 * The single swappable seam of the Signing ACL: the raw e-Sign vendor calls. The fixed
 * {@code SignatureAclService} (persistence, idempotency, audit) calls this; only this bean is replaced
 * (fake → vendor sandbox → production) at the Production gate. Phase-1 impl is
 * {@link StubSigningVendorClient}.
 */
public interface SigningVendorClient {

    InitiateAck initiate(UUID vsrId, byte[] docHash, String signerRef, SignMethod signMethod);

    CompletionAck complete(UUID vsrId);

    /** @param vendorSessionUrl the hosted signing URL the signer is redirected to */
    record InitiateAck(String vendorSessionUrl) {
    }

    /** @param certSerial the digital-certificate serial assigned on a successful signature */
    record CompletionAck(String certSerial) {
    }
}
