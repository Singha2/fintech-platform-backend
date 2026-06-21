package com.arthvritt.platform.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lean in-process stub {@link NotificationChannel} (M3a / DL-BE-016; recast in M5d). It does not send
 * anything — it records each request so dev/tests can read what a real handset would have received, and
 * logs at DEBUG (dev only). The {@code sys_notification_dispatch} lifecycle around it is
 * {@link NotificationService}'s (M5d); a real SES/SNS/Twilio adapter swaps this out at Production.
 *
 * <p>The DEBUG log records only param <i>keys</i>, never values — so the OTP code is not written to
 * logs even in dev. The code is still readable via {@link #lastCodeFor} for tests. Recent sends are
 * retained in a bounded buffer so a long dev session doesn't leak memory (or hoard secrets).
 */
@Slf4j
@Component
public class StubNotifier implements NotificationChannel {

    /** Test hook: a request with this template id makes the channel throw (to exercise fire-and-forget). */
    static final String FAIL_TEMPLATE = "stub_fail";
    /** Test hook: a request with this template id makes the channel return no provider ref (null). */
    static final String NO_REF_TEMPLATE = "stub_no_ref";
    private static final int MAX_RETAINED = 500;

    private final List<NotificationRequest> sent = new CopyOnWriteArrayList<>();

    @Override
    public String send(NotificationRequest request) {
        if (FAIL_TEMPLATE.equals(request.templateId())) {
            throw new IllegalStateException("stub channel forced failure");
        }
        sent.add(request);
        while (sent.size() > MAX_RETAINED) {
            sent.remove(0);
        }
        log.debug("[STUB notification] channel={} template={} recipient={} paramKeys={}",
                request.channel(), request.templateId(), request.recipientIdentityId(),
                request.params().keySet());
        if (NO_REF_TEMPLATE.equals(request.templateId())) {
            return null; // delivered, but the vendor gave no reference — service must treat as a failure
        }
        return "stub:" + request.channel() + ":" + request.recipientIdentityId();
    }

    /** Test/dev: clears recorded sends (the stub is a shared singleton across the cached context). */
    public void clear() {
        sent.clear();
    }

    public List<NotificationRequest> sent() {
        return List.copyOf(sent);
    }

    /** The most recent message sent to this identity, if any. */
    public Optional<NotificationRequest> lastFor(UUID recipientIdentityId) {
        for (int i = sent.size() - 1; i >= 0; i--) {
            NotificationRequest r = sent.get(i);
            if (recipientIdentityId.equals(r.recipientIdentityId())) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    /** Convenience for OTP tests: the {@code code} param of the most recent message to this identity. */
    public Optional<String> lastCodeFor(UUID recipientIdentityId) {
        return lastFor(recipientIdentityId).map(r -> (String) r.params().get("code"));
    }
}
