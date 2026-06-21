package com.arthvritt.platform.notification;

import com.arthvritt.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * M5d invariant tests (see docs/modules/M5d-notifications.md §7): the BC15 dispatch lifecycle — a real
 * port over a swappable channel, with fire-and-forget and no-OTP/PII-in-payload as the headline.
 * Integration against Testcontainers. (The M3a OTP regression is covered by the whole auth/admin suite.)
 */
class NotificationAclTest extends AbstractIntegrationTest {

    @Autowired private NotificationService notifications;
    @Autowired private StubNotifier channel;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearChannel() {
        channel.clear();
    }

    @Test
    void a_dispatch_is_recorded_sent_and_audited() { // INV-3, INV-4
        UUID recipient = UUID.randomUUID();
        notifications.send(new NotificationRequest(recipient, "sms", "login_otp", Map.of("greeting", "Hi")));

        UUID dispatchId = jdbc.queryForObject(
                "SELECT dispatch_id FROM sys_notification_dispatch WHERE recipient_identity_id = ?", UUID.class, recipient);
        assertThat(jdbc.queryForObject("SELECT status::text FROM sys_notification_dispatch WHERE dispatch_id = ?",
                String.class, dispatchId)).isEqualTo("sent");
        assertThat(jdbc.queryForObject("SELECT provider_ref FROM sys_notification_dispatch WHERE dispatch_id = ?",
                String.class, dispatchId)).startsWith("stub:");
        assertThat(envelopes("notifications.Notification.Dispatched", dispatchId)).isEqualTo(1);
        assertThat(channel.lastFor(recipient)).isPresent(); // the channel delivered it
    }

    @Test
    void the_persisted_payload_carries_no_otp_or_pii() { // INV-2
        UUID recipient = UUID.randomUUID();
        notifications.send(new NotificationRequest(recipient, "sms", "login_otp",
                Map.of("code", "123456", "phone", "+919800000001", "greeting", "Hi")));

        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM sys_notification_dispatch WHERE recipient_identity_id = ?",
                String.class, recipient);
        assertThat(payload).contains("greeting").doesNotContain("123456").doesNotContain("9800000001");
        // The channel still received the full params (so a real handset would get the code).
        assertThat(channel.lastFor(recipient).orElseThrow().params()).containsEntry("code", "123456");
    }

    @Test
    void a_channel_failure_is_recorded_and_not_propagated() { // INV-1 (fire-and-forget)
        UUID recipient = UUID.randomUUID();

        assertThatCode(() -> notifications.send(
                new NotificationRequest(recipient, "sms", "stub_fail", Map.of()))).doesNotThrowAnyException();

        assertThat(jdbc.queryForObject("SELECT status::text FROM sys_notification_dispatch WHERE recipient_identity_id = ?",
                String.class, recipient)).isEqualTo("failed");
        UUID dispatchId = jdbc.queryForObject(
                "SELECT dispatch_id FROM sys_notification_dispatch WHERE recipient_identity_id = ?", UUID.class, recipient);
        assertThat(envelopes("notifications.Notification.DispatchFailed", dispatchId)).isEqualTo(1);
    }

    @Test
    void a_null_provider_ref_is_recorded_as_failed_not_sent() { // INV-1 / provider_ref CHECK guard
        UUID recipient = UUID.randomUUID();

        assertThatCode(() -> notifications.send(
                new NotificationRequest(recipient, "sms", "stub_no_ref", Map.of()))).doesNotThrowAnyException();

        String status = jdbc.queryForObject(
                "SELECT status::text FROM sys_notification_dispatch WHERE recipient_identity_id = ?",
                String.class, recipient);
        assertThat(status).isEqualTo("failed"); // not 'sent' with a NULL provider_ref (CHECK would have poisoned the tx)
        UUID dispatchId = jdbc.queryForObject(
                "SELECT dispatch_id FROM sys_notification_dispatch WHERE recipient_identity_id = ?", UUID.class, recipient);
        assertThat(envelopes("notifications.Notification.DispatchFailed", dispatchId)).isEqualTo(1);
    }

    @Test
    void a_nested_param_value_is_dropped_from_the_persisted_payload() { // INV-2 (defence-in-depth)
        UUID recipient = UUID.randomUUID();
        notifications.send(new NotificationRequest(recipient, "sms", "login_otp",
                Map.of("greeting", "Hi", "borrower", Map.of("pan", "ABCDE1234F"))));

        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM sys_notification_dispatch WHERE recipient_identity_id = ?",
                String.class, recipient);
        // The scalar template var survives; the structured value (which could smuggle PII) does not.
        assertThat(payload).contains("greeting").doesNotContain("borrower").doesNotContain("ABCDE1234F");
    }

    private int envelopes(String eventType, UUID aggregateId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, eventType, aggregateId);
    }
}
