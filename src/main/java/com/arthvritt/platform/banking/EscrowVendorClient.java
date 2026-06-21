package com.arthvritt.platform.banking;

import com.arthvritt.platform.banking.EscrowPort.PayoutLeg;

import java.util.List;
import java.util.UUID;

/**
 * The single swappable seam of the Banking ACL: the raw escrow-vendor calls. The fixed
 * {@code EscrowAclService} (persistence, idempotency, audit) calls this; only this bean is replaced
 * (fake → vendor sandbox → production) at the Production gate. Phase-1 impl is
 * {@link StubEscrowVendorClient}. Each ack carries the vendor-assigned identifiers (account/UTR), the
 * {@code vendorEventId} (the webhook dedup key), and the verbatim payload bytes (only its hash persists).
 */
public interface EscrowVendorClient {

    VaAck createVa(UUID clientInstructionId, UUID vaRef);

    PayoutAck executePayout(UUID clientInstructionId, long amountPaise, String beneficiary);

    MultiLegAck executeMultiLeg(UUID clientInstructionId, List<PayoutLeg> legs);

    PayoutAck executeRefund(UUID clientInstructionId, long amountPaise);

    record VaAck(String ifsc, String accountNo, String vendorEventId, byte[] rawPayload) {
    }

    record PayoutAck(String utr, String vendorEventId, byte[] rawPayload) {
    }

    record MultiLegAck(List<String> legUtrs, String vendorEventId, byte[] rawPayload) {
    }
}
