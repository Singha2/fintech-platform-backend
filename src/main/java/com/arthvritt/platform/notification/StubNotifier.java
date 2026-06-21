package com.arthvritt.platform.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lean in-process stub for {@link NotificationPort} (M3a / DL-BE-016). It does not send anything —
 * it records each request so dev/tests can read what a real handset would have received, and logs at
 * DEBUG (dev only). It deliberately does NOT write {@code sys_notification_dispatch}; that lifecycle
 * lands in M5 with the real provider.
 *
 * <p>The DEBUG log records only param <i>keys</i>, never values — so the OTP code is not written to
 * logs even in dev. The code is still readable via {@link #lastCodeFor} for tests. Recent sends are
 * retained in a bounded buffer so a long dev session doesn't leak memory (or hoard secrets).
 */
@Slf4j
@Component
public class StubNotifier implements NotificationPort {

    private static final int MAX_RETAINED = 500;

    private final List<NotificationRequest> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(NotificationRequest request) {
        sent.add(request);
        while (sent.size() > MAX_RETAINED) {
            sent.remove(0);
        }
        log.debug("[STUB notification] channel={} template={} recipient={} paramKeys={}",
                request.channel(), request.templateId(), request.recipientIdentityId(),
                request.params().keySet());
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
