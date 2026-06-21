package com.arthvritt.platform.notification;

/**
 * BC15 outbound-notification port (Anti-Corruption Layer). Real interface; the Phase-1 adapter is an
 * in-process stub ({@link StubNotifier}). A real SMS/email provider is swapped in at M5 / Production
 * with no change to callers (e.g. M3a OTP delivery).
 */
public interface NotificationPort {

    void send(NotificationRequest request);
}
