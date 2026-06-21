package com.arthvritt.platform.banking;

import com.arthvritt.platform.banking.EscrowPort.PayoutLeg;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase-1 fake escrow provider (DL-046). Deterministic IFSC/account on {@code createVa};
 * <b>auto-succeeds</b> payouts/refunds with a fake UTR. All vendor-assigned values are deterministic
 * functions of the {@code clientInstructionId}, so they are stable across retries and unique per
 * instruction (the {@code vendor_event_id} UNIQUE constraint). The real adapter is a drop-in later.
 */
@Component
public class StubEscrowVendorClient implements EscrowVendorClient {

    private final ObjectMapper mapper;

    public StubEscrowVendorClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public VaAck createVa(UUID clientInstructionId, UUID vaRef) {
        String ifsc = "STUB0000001";
        String accountNo = "VA" + shortId(vaRef);
        String eventId = "stub-va-" + clientInstructionId;
        byte[] raw = payload(Map.of("op", "create_va", "ifsc", ifsc, "account_no", accountNo, "va_ref", vaRef.toString()));
        return new VaAck(ifsc, accountNo, eventId, raw);
    }

    @Override
    public PayoutAck executePayout(UUID clientInstructionId, long amountPaise, String beneficiary) {
        String utr = utr("P", clientInstructionId);
        byte[] raw = payload(Map.of("op", "payout_single", "utr", utr, "amount", amountPaise, "beneficiary", beneficiary));
        return new PayoutAck(utr, "stub-payout-" + clientInstructionId, raw);
    }

    @Override
    public MultiLegAck executeMultiLeg(UUID clientInstructionId, List<PayoutLeg> legs) {
        List<String> legUtrs = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) {
            legUtrs.add(utr("L" + i + "-", clientInstructionId)); // distinct per leg, deterministic
        }
        byte[] raw = payload(Map.of("op", "payout_multi_leg", "legs", legUtrs));
        return new MultiLegAck(legUtrs, "stub-ml-" + clientInstructionId, raw);
    }

    @Override
    public PayoutAck executeRefund(UUID clientInstructionId, long amountPaise) {
        String utr = utr("R", clientInstructionId);
        byte[] raw = payload(Map.of("op", "refund", "utr", utr, "amount", amountPaise));
        return new PayoutAck(utr, "stub-refund-" + clientInstructionId, raw);
    }

    private static String utr(String prefix, UUID clientInstructionId) {
        return "STUBUTR" + prefix + shortId(clientInstructionId);
    }

    private static String shortId(UUID id) {
        return id.toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private byte[] payload(Map<String, Object> body) {
        try {
            return mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialise stub escrow payload", e);
        }
    }
}
