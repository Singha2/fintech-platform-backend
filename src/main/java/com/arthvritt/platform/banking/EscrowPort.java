package com.arthvritt.platform.banking;

import java.util.List;
import java.util.UUID;

/**
 * BC18 Banking/Escrow anti-corruption port — the money operations BC4 Settlement (M13) calls. Each
 * outbound op is keyed by a caller-supplied {@code clientInstructionId} (= the {@code
 * vendor_instruction_id}, the idempotency key, VI.1): re-issuing it is a no-op that returns the
 * original outcome. {@code processInflowWebhook} is the inbound entry (the real HTTP handler calls it
 * after HMAC; the stub fires it/the payout webhooks in-process). No vendor model crosses this port.
 */
public interface EscrowPort {

    VaResult createVa(UUID clientInstructionId, UUID vaRef);

    PayoutResult instructPayoutSingle(UUID clientInstructionId, UUID payoutRef, long amountPaise, String beneficiary);

    MultiLegResult instructPayoutMultiLeg(UUID clientInstructionId, UUID payoutRef, List<PayoutLeg> legs);

    RefundResult instructRefund(UUID clientInstructionId, UUID inflowRef, long amountPaise);

    WebhookOutcome processInflowWebhook(UUID vaRef, long amountPaise, String utr, String vendorEventId);

    record VaResult(String ifsc, String accountNo) {
    }

    record PayoutResult(String utr) {
    }

    record RefundResult(String utr) {
    }

    record MultiLegResult(List<String> legUtrs) {
    }

    record PayoutLeg(String beneficiary, long amountPaise) {
    }

    /** Inbound-webhook outcome: applied (first delivery) or dropped as a duplicate (VI.3). */
    enum WebhookOutcome {
        APPLIED,
        DUPLICATE_DROPPED
    }
}
