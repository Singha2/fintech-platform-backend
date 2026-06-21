package com.arthvritt.platform.notification;

/**
 * BC15 outbound-notification port (Anti-Corruption Layer). Real interface; the sole impl is
 * {@link NotificationService} (M5d), which records the {@code sys_notification_dispatch} lifecycle and
 * audits, then delegates raw delivery to a swappable {@link NotificationChannel} (Phase-1:
 * {@link StubNotifier}). A real SMS/email provider is swapped in at the channel, with no change to
 * callers (e.g. M3a OTP delivery).
 */
public interface NotificationPort {

    void send(NotificationRequest request);
}
