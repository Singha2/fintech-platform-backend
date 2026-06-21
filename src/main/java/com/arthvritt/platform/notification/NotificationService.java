package com.arthvritt.platform.notification;

import com.arthvritt.platform.acl.AbstractAclService;
import com.arthvritt.platform.audit.AuditLog;
import com.arthvritt.platform.shared.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BC15 Notifications — the <b>fixed</b> half (the {@code sys_notification_dispatch} lifecycle + audit)
 * on {@link AbstractAclService}, and the sole {@link NotificationPort} impl (M5d completes M3a's thin
 * slice). It records the dispatch, delivers via the swappable {@link NotificationChannel}, records the
 * outcome, and audits. <b>Fire-and-forget</b> (ND.1): a delivery failure is recorded, never rethrown,
 * so the caller's already-committed business state is unaffected. The persisted payload carries no
 * OTP/PII (ND.2).
 */
@Slf4j
@Service
public class NotificationService extends AbstractAclService implements NotificationPort {

    /** Keys that must never be persisted to {@code sys_notification_dispatch.payload} (ND.2, C14/C15). */
    private static final Set<String> SENSITIVE = Set.of(
            "code", "otp", "password", "secret", "phone", "mobile", "email", "pan", "aadhaar");

    private final JdbcTemplate jdbc;
    private final NotificationChannel channel;
    private final ObjectMapper mapper;

    public NotificationService(JdbcTemplate jdbc, NotificationChannel channel,
                               AuditLog auditLog, ObjectMapper mapper) {
        super(auditLog, "notifications", "notification_acl", "NotificationDispatch");
        this.jdbc = jdbc;
        this.channel = channel;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void send(NotificationRequest request) {
        UUID dispatchId = Ids.newId();
        // payload holds non-sensitive template vars ONLY (ND.2) — the OTP/phone never persist here.
        jdbc.update("INSERT INTO sys_notification_dispatch "
                        + "(dispatch_id, recipient_identity_id, channel, template_id, payload, status) "
                        + "VALUES (?, ?, ?::notification_channel_enum, ?, ?::jsonb, 'queued')",
                dispatchId, request.recipientIdentityId(), request.channel(), request.templateId(),
                safePayload(request.params()));

        // Settle the delivery outcome FIRST — every channel failure is contained here (ND.1
        // fire-and-forget), so it can never escape into the caller's afterCommit callback. Only then
        // do we record the outcome + audit, so an audit hiccup can't mislabel a delivered message.
        String providerRef = deliver(request);
        if (providerRef != null) {
            jdbc.update("UPDATE sys_notification_dispatch SET status = 'sent', provider_ref = ? WHERE dispatch_id = ?",
                    providerRef, dispatchId);
            auditAclEvent(dispatchId, "notifications.Notification.Dispatched", meta(request));
        } else {
            jdbc.update("UPDATE sys_notification_dispatch SET status = 'failed' WHERE dispatch_id = ?", dispatchId);
            auditAclEvent(dispatchId, "notifications.Notification.DispatchFailed", meta(request));
        }
    }

    /**
     * Fire-and-forget delivery (ND.1): hands the full request to the swappable channel and returns the
     * vendor provider ref, or {@code null} when the channel threw or returned no usable ref. A null ref
     * must NOT reach the {@code 'sent'} UPDATE — that would breach the provider_ref CHECK and poison the
     * transaction; treating it as a failure keeps the contract clean for any real adapter.
     */
    private String deliver(NotificationRequest request) {
        try {
            String providerRef = channel.send(request); // the channel gets the full params to deliver
            if (providerRef == null || providerRef.isBlank()) {
                log.warn("notification channel returned no provider ref: template={} recipient={}",
                        request.templateId(), request.recipientIdentityId());
                return null;
            }
            return providerRef;
        } catch (RuntimeException e) {
            log.warn("notification channel delivery failed: template={} recipient={}: {}",
                    request.templateId(), request.recipientIdentityId(), e.toString());
            return null;
        }
    }

    private static Map<String, Object> meta(NotificationRequest request) {
        return Map.of("recipient_identity_id", request.recipientIdentityId().toString(),
                "channel", request.channel(), "template_id", request.templateId());
    }

    private String safePayload(Map<String, Object> params) {
        Map<String, Object> safe = new LinkedHashMap<>();
        params.forEach((k, v) -> {
            if (SENSITIVE.contains(k.toLowerCase())) {
                return; // denylisted key — never persisted (ND.2)
            }
            // Persist scalars ONLY. A structured value (Map/Collection/array) could smuggle PII past the
            // key denylist (e.g. {"borrower": {"pan": ...}}); templates use scalar vars, so drop the rest.
            if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) {
                safe.put(k, v);
            } else {
                log.warn("dropping non-scalar notification param '{}' ({}) from persisted payload (ND.2)",
                        k, v.getClass().getSimpleName());
            }
        });
        try {
            return mapper.writeValueAsString(safe);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialise notification payload", e);
        }
    }
}
