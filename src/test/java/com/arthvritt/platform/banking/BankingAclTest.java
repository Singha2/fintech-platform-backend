package com.arthvritt.platform.banking;

import com.arthvritt.platform.AbstractIntegrationTest;
import com.arthvritt.platform.shared.error.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M5b invariant tests (see docs/modules/M5b-banking-escrow-acl.md §7): the Banking/Escrow ACL — a real
 * port behind a deterministic stub, with client_instruction_id idempotency and vendor_event_id webhook
 * dedup as the headline. Integration against Testcontainers.
 */
class BankingAclTest extends AbstractIntegrationTest {

    @Autowired private EscrowAclService escrow;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void create_va_returns_deterministic_details_and_audits() { // INV-5, INV-6, INV-7
        UUID instructionId = UUID.randomUUID();
        EscrowPort.VaResult result = escrow.createVa(instructionId, UUID.randomUUID());

        assertThat(result.ifsc()).isNotBlank();
        assertThat(result.accountNo()).startsWith("VA");
        assertThat(status(instructionId)).isEqualTo("executed");
        assertThat(envelopes("banking.Va.LifecycleObserved", instructionId)).isEqualTo(1);
    }

    @Test
    void a_payout_executes_with_a_fake_utr_and_is_idempotent_on_the_instruction_id() { // INV-1, INV-7
        UUID instructionId = UUID.randomUUID();
        EscrowPort.PayoutResult first = escrow.instructPayoutSingle(instructionId, UUID.randomUUID(), 100_00L, "ACME");

        assertThat(first.utr()).startsWith("STUBUTR");
        assertThat(status(instructionId)).isEqualTo("executed");
        assertThat(envelopes("banking.PayoutLegWebhookProcessed", instructionId)).isEqualTo(1);

        // Retry with the same client_instruction_id → no second instruction / no second webhook.
        EscrowPort.PayoutResult retry = escrow.instructPayoutSingle(instructionId, UUID.randomUUID(), 100_00L, "ACME");
        assertThat(retry.utr()).isEqualTo(first.utr());
        assertThat(instructionCount(instructionId)).isEqualTo(1);
        assertThat(envelopes("banking.PayoutLegWebhookProcessed", instructionId)).isEqualTo(1);
    }

    @Test
    void a_multi_leg_payout_preserves_leg_order_across_an_idempotent_retry() { // INV-1 (UTR↔leg attribution)
        UUID instructionId = UUID.randomUUID();
        java.util.List<EscrowPort.PayoutLeg> legs = java.util.List.of(
                new EscrowPort.PayoutLeg("INV-A", 100_00L),
                new EscrowPort.PayoutLeg("INV-B", 200_00L),
                new EscrowPort.PayoutLeg("INV-C", 50_00L));
        EscrowPort.MultiLegResult first = escrow.instructPayoutMultiLeg(instructionId, UUID.randomUUID(), legs);
        assertThat(first.legUtrs()).hasSize(3);

        EscrowPort.MultiLegResult retry = escrow.instructPayoutMultiLeg(instructionId, UUID.randomUUID(), legs);
        assertThat(retry.legUtrs()).containsExactlyElementsOf(first.legUtrs()); // same order, no re-execution
        assertThat(instructionCount(instructionId)).isEqualTo(1);
    }

    @Test
    void an_inflow_is_recorded_provisional() { // INV-3, INV-4
        UUID vaRef = UUID.randomUUID();
        EscrowPort.WebhookOutcome outcome = escrow.processInflowWebhook(vaRef, 250_00L, "UTR-IN-1", "esc-evt-1");

        assertThat(outcome).isEqualTo(EscrowPort.WebhookOutcome.APPLIED);
        assertThat(jdbc.queryForObject(
                "SELECT status::text FROM gate_inflow_observation WHERE vendor_event_id = ?", String.class, "esc-evt-1"))
                .isEqualTo("provisional");
    }

    @Test
    void a_duplicate_inflow_webhook_is_dropped_with_no_state_change() { // INV-2
        UUID vaRef = UUID.randomUUID();
        escrow.processInflowWebhook(vaRef, 250_00L, "UTR-IN-2", "esc-evt-2");

        // Same vendor_event_id again → dropped.
        EscrowPort.WebhookOutcome dup = escrow.processInflowWebhook(vaRef, 250_00L, "UTR-IN-2b", "esc-evt-2");
        assertThat(dup).isEqualTo(EscrowPort.WebhookOutcome.DUPLICATE_DROPPED);
        // Same utr (different event id) → also dropped (UNIQUE utr).
        EscrowPort.WebhookOutcome dupUtr = escrow.processInflowWebhook(vaRef, 250_00L, "UTR-IN-2", "esc-evt-2c");
        assertThat(dupUtr).isEqualTo(EscrowPort.WebhookOutcome.DUPLICATE_DROPPED);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM gate_inflow_observation WHERE va_id = ?",
                Integer.class, vaRef)).isEqualTo(1); // only the original
    }

    @Test
    void a_zero_amount_inflow_is_rejected() { // INV-4
        assertThatThrownBy(() -> escrow.processInflowWebhook(UUID.randomUUID(), 0L, "UTR-Z", "esc-evt-z"))
                .isInstanceOf(ValidationException.class);
    }

    private String status(UUID instructionId) {
        return jdbc.queryForObject("SELECT status::text FROM gate_vendor_instruction WHERE vendor_instruction_id = ?",
                String.class, instructionId);
    }

    private int instructionCount(UUID instructionId) {
        return jdbc.queryForObject("SELECT count(*) FROM gate_vendor_instruction WHERE vendor_instruction_id = ?",
                Integer.class, instructionId);
    }

    private int envelopes(String eventType, UUID aggregateId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, eventType, aggregateId);
    }
}
