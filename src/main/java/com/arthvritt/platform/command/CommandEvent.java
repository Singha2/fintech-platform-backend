package com.arthvritt.platform.command;

import java.util.Map;

/**
 * What a {@link CommandHandler} produces on success: the business fact to audit. The gateway wraps
 * this into a full {@code AuditEventEnvelope} (stamping actor, {@code command_id}, correlation), so
 * commands stay thin and #5 is centralised. When {@code stateTransition} is true the DB requires both
 * {@code beforeState} and {@code afterState} (the {@code sys_audit_event} snapshot CHECK).
 *
 * @param eventType        e.g. {@code admin_iam.AdminUser.Disabled}
 * @param aggregateVersion the aggregate's version <i>after</i> the mutation (≥1)
 * @param payload          event payload (no secrets; masked per BC16 where applicable)
 * @param beforeState      pre-mutation snapshot (required when {@code stateTransition})
 * @param afterState       post-mutation snapshot (required when {@code stateTransition})
 * @param stateTransition  true for state-changing commands
 */
public record CommandEvent(
        String eventType,
        int aggregateVersion,
        Map<String, Object> payload,
        Map<String, Object> beforeState,
        Map<String, Object> afterState,
        boolean stateTransition) {
}
