package com.arthvritt.platform.banking;

import com.arthvritt.platform.acl.AbstractAclService;
import com.arthvritt.platform.audit.AuditLog;
import com.arthvritt.platform.shared.Ids;
import com.arthvritt.platform.shared.error.ValidationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BC18 Banking/Escrow ACL — the <b>fixed</b> half (gate_vendor_instruction / gate_inflow_observation
 * persistence, ACL idempotency, webhook dedup, audit). Calls the swappable {@link EscrowVendorClient}
 * for the raw vendor result. Idempotency is the ACL keys — {@code client_instruction_id} (VI.1) and
 * {@code vendor_event_id} (VI.3) — not the M4a {@code command_id} store; the payout-approval
 * maker-checker/MFA is BC4's (PI.5), upstream of this ACL. The vendor-assigned values (IFSC/UTR) flow
 * to BC4 through the webhook envelopes (no dedicated columns), so an idempotent retry re-reads them
 * from the audit stream.
 */
@Service
public class EscrowAclService extends AbstractAclService implements EscrowPort {

    private final JdbcTemplate jdbc;
    private final EscrowVendorClient vendorClient;

    public EscrowAclService(JdbcTemplate jdbc, EscrowVendorClient vendorClient, AuditLog auditLog) {
        super(auditLog, "banking", "escrow_acl", "VendorInstruction");
        this.jdbc = jdbc;
        this.vendorClient = vendorClient;
    }

    @Override
    @Transactional
    public VaResult createVa(UUID clientInstructionId, UUID vaRef) {
        if (claim(clientInstructionId, VendorInstructionType.CREATE_VA, null) == 0) { // idempotent retry/concurrent
            return new VaResult(envelopeField(clientInstructionId, "banking.Va.LifecycleObserved", "ifsc"),
                    envelopeField(clientInstructionId, "banking.Va.LifecycleObserved", "account_no"));
        }
        EscrowVendorClient.VaAck ack = vendorClient.createVa(clientInstructionId, vaRef);
        complete(clientInstructionId, ack.vendorEventId(), ack.rawPayload());
        auditAclEvent(clientInstructionId, "banking.Va.LifecycleObserved", Map.of(
                "va_ref", vaRef.toString(), "ifsc", ack.ifsc(), "account_no", ack.accountNo(),
                "vendor_event_id", ack.vendorEventId()));
        return new VaResult(ack.ifsc(), ack.accountNo());
    }

    @Override
    @Transactional
    public PayoutResult instructPayoutSingle(UUID clientInstructionId, UUID payoutRef, long amountPaise,
                                             String beneficiary) {
        requirePositive(amountPaise);
        if (claim(clientInstructionId, VendorInstructionType.PAYOUT_SINGLE, payoutRef) == 0) {
            return new PayoutResult(envelopeField(clientInstructionId, "banking.PayoutLegWebhookProcessed", "utr"));
        }
        EscrowVendorClient.PayoutAck ack = vendorClient.executePayout(clientInstructionId, amountPaise, beneficiary);
        complete(clientInstructionId, ack.vendorEventId(), ack.rawPayload());
        auditAclEvent(clientInstructionId, "banking.PayoutLegWebhookProcessed", Map.of(
                "payout_ref", payoutRef.toString(), "utr", ack.utr(),
                "vendor_event_id", ack.vendorEventId(), "amount", amountPaise));
        return new PayoutResult(ack.utr());
    }

    @Override
    @Transactional
    public MultiLegResult instructPayoutMultiLeg(UUID clientInstructionId, UUID payoutRef, List<PayoutLeg> legs) {
        legs.forEach(leg -> requirePositive(leg.amountPaise()));
        if (claim(clientInstructionId, VendorInstructionType.PAYOUT_MULTI_LEG, payoutRef) == 0) {
            return new MultiLegResult(envelopeFields(clientInstructionId, "banking.PayoutLegWebhookProcessed", "utr"));
        }
        EscrowVendorClient.MultiLegAck ack = vendorClient.executeMultiLeg(clientInstructionId, legs);
        complete(clientInstructionId, ack.vendorEventId(), ack.rawPayload());
        // One PayoutLegWebhookProcessed per leg — the stub auto-succeeds all (partial-failure remediation
        // is BC4/G11). Distinct UTR per leg.
        for (int i = 0; i < ack.legUtrs().size(); i++) {
            auditAclEvent(clientInstructionId, "banking.PayoutLegWebhookProcessed", Map.of(
                    "payout_ref", payoutRef.toString(), "leg_index", i, "utr", ack.legUtrs().get(i),
                    "vendor_event_id", ack.vendorEventId(), "amount", legs.get(i).amountPaise()));
        }
        return new MultiLegResult(ack.legUtrs());
    }

    @Override
    @Transactional
    public RefundResult instructRefund(UUID clientInstructionId, UUID inflowRef, long amountPaise) {
        requirePositive(amountPaise);
        if (claim(clientInstructionId, VendorInstructionType.REFUND, inflowRef) == 0) {
            return new RefundResult(envelopeField(clientInstructionId, "banking.RefundWebhookProcessed", "utr"));
        }
        EscrowVendorClient.PayoutAck ack = vendorClient.executeRefund(clientInstructionId, amountPaise);
        complete(clientInstructionId, ack.vendorEventId(), ack.rawPayload());
        auditAclEvent(clientInstructionId, "banking.RefundWebhookProcessed", Map.of(
                "inflow_ref", inflowRef.toString(), "utr", ack.utr(),
                "vendor_event_id", ack.vendorEventId(), "amount", amountPaise));
        return new RefundResult(ack.utr());
    }

    @Override
    @Transactional
    public WebhookOutcome processInflowWebhook(UUID vaRef, long amountPaise, String utr, String vendorEventId) {
        requirePositive(amountPaise);
        UUID inflowId = Ids.newId();
        // ON CONFLICT DO NOTHING (no target) catches BOTH unique constraints (vendor_event_id, utr) and
        // — unlike a caught DuplicateKeyException — does not abort the transaction, so the audit append
        // below still runs.
        int inserted = jdbc.update("INSERT INTO gate_inflow_observation "
                        + "(inflow_id, va_id, amount, utr, observed_at, status, vendor_event_id) "
                        + "VALUES (?, ?, ?, ?, now(), 'provisional', ?) ON CONFLICT DO NOTHING",
                inflowId, vaRef, amountPaise, utr, vendorEventId);
        if (inserted == 0) {
            // Re-delivery: same vendor_event_id or same utr — drop, no state change (VI.3). File the
            // drop under the ORIGINAL inflow it duplicates, so the two correlate by aggregate_id.
            UUID original = jdbc.query(
                    "SELECT inflow_id FROM gate_inflow_observation WHERE vendor_event_id = ? OR utr = ? LIMIT 1",
                    rs -> rs.next() ? rs.getObject(1, UUID.class) : null, vendorEventId, utr);
            auditAclEvent(original == null ? vaRef : original, "banking.Webhook.DuplicateDropped", Map.of(
                    "kind", "inflow", "va_ref", vaRef.toString(), "utr", utr, "vendor_event_id", vendorEventId));
            return WebhookOutcome.DUPLICATE_DROPPED;
        }
        auditAclEvent(inflowId, "banking.InflowWebhookProcessed", Map.of(
                "va_ref", vaRef.toString(), "amount", amountPaise, "utr", utr, "vendor_event_id", vendorEventId));
        return WebhookOutcome.APPLIED;
    }

    /**
     * Records a failed inbound-webhook HMAC verification (B4 §5.1, C10) — one of the few cases where an
     * unauthenticated source produces an audit fact, because a signature failure is security-relevant. The
     * raw payload is never stored; only its SHA-256 is recorded. Runs in its own transaction so the envelope
     * persists independently of the 401 response.
     */
    @Transactional
    public void recordSignatureInvalid(String vendor, byte[] rawBody) {
        auditAclEvent(Ids.newId(), "banking.WebhookSignature.Invalid", Map.of(
                "vendor", vendor, "payload_sha256", java.util.HexFormat.of().formatHex(sha256(rawBody))));
    }

    // --- internals -----------------------------------------------------------------------------

    /**
     * Atomically claims the {@code client_instruction_id} (VI.1). Returns 1 if this caller claimed it
     * (proceed to execute), 0 if it already existed (a retry or a concurrent caller that won — re-read
     * the original outcome). `ON CONFLICT DO NOTHING` is atomic, unlike an exists()-then-insert; a
     * concurrent insert blocks on the PK index until the first commits, then returns 0.
     */
    private int claim(UUID clientInstructionId, VendorInstructionType type, UUID payoutRef) {
        return jdbc.update("INSERT INTO gate_vendor_instruction "
                        + "(vendor_instruction_id, instruction_type, linked_payout_instruction_id, status) "
                        + "VALUES (?, ?::vendor_instruction_type_enum, ?, 'sent') "
                        + "ON CONFLICT (vendor_instruction_id) DO NOTHING",
                clientInstructionId, type.wire(), payoutRef);
    }

    private void complete(UUID clientInstructionId, String vendorEventId, byte[] rawPayload) {
        jdbc.update("UPDATE gate_vendor_instruction SET status = 'executed', vendor_event_id = ?, "
                        + "vendor_payload_hash = ?, hmac_verified_at = now() WHERE vendor_instruction_id = ?",
                vendorEventId, sha256(rawPayload), clientInstructionId);
    }

    private static void requirePositive(long amountPaise) {
        if (amountPaise <= 0) {
            throw new ValidationException("amount must be positive paise, was " + amountPaise);
        }
    }

    private String envelopeField(UUID aggregateId, String eventType, String field) {
        return jdbc.query("SELECT payload->>? FROM sys_audit_event WHERE aggregate_id = ? AND event_type = ? "
                        + "ORDER BY recorded_at DESC LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null, field, aggregateId, eventType);
    }

    /** Re-reads per-leg UTRs in leg order — ordered by the explicit {@code leg_index}, never by
     *  {@code recorded_at} (same-microsecond legs would otherwise permute and misattribute UTRs). */
    private List<String> envelopeFields(UUID aggregateId, String eventType, String field) {
        return jdbc.queryForList("SELECT payload->>? FROM sys_audit_event WHERE aggregate_id = ? AND event_type = ? "
                        + "ORDER BY (payload->>'leg_index')::int",
                String.class, field, aggregateId, eventType);
    }

}
