package com.arthvritt.platform.notification;

/**
 * The swappable seam of the Notifications ACL (BC15, M5d): the raw email/SMS delivery. The fixed
 * {@link com.arthvritt.platform.notification.NotificationService} (the {@link NotificationPort} impl —
 * dispatch persistence, audit) calls this; only this bean is replaced (fake → SES/SNS/Twilio) at the
 * Production gate. Phase-1 impl is {@link StubNotifier}.
 *
 * <p>The channel receives the full {@link NotificationRequest} params (it must, to render and deliver
 * the message — including the OTP); the service persists only non-sensitive template vars (ND.2).
 */
public interface NotificationChannel {

    /** Delivers the message and returns the vendor-assigned provider reference. */
    String send(NotificationRequest request);
}
