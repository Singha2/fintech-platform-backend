package com.arthvritt.platform.notification;

import java.util.Map;
import java.util.UUID;

/**
 * A request to deliver one message to an identity over a channel (BC15). {@code params} holds the
 * template variables (e.g. the OTP {@code code}). Thin slice of BC15 — the full dispatch lifecycle
 * (templates, retries, {@code sys_notification_dispatch}) is M5.
 */
public record NotificationRequest(
        UUID recipientIdentityId,
        String channel,
        String templateId,
        Map<String, Object> params) {

    public NotificationRequest {
        params = params == null ? Map.of() : Map.copyOf(params); // immutable; no aliasing of the caller's map
    }
}
