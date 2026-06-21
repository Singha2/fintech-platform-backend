package com.arthvritt.platform.verification;

import com.arthvritt.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5a invariant tests (see docs/modules/M5a-verification-acl.md §7): the Verification ACL — a real
 * port behind a deterministic auto-pass stub, with the cache/TTL idempotency as the headline.
 * Integration against Testcontainers.
 */
class VerificationAclTest extends AbstractIntegrationTest {

    @Autowired private VerificationService verification;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void a_verification_completes_with_deterministic_fields_a_ttl_and_audit() { // INV-1, INV-3, INV-6
        UUID subject = UUID.randomUUID();
        VerificationResult result = verification.verifyPan(subject, "ABCDE1234F");

        assertThat(result.status()).isEqualTo(VerificationStatus.COMPLETED);
        assertThat(result.extractedFields()).containsEntry("pan_status", "VALID");
        assertThat(result.ttlUntil()).isAfter(Instant.now().plusSeconds(300L * 24 * 3600)); // ~> 300 days

        assertThat(jdbc.queryForObject("SELECT status::text FROM gate_verification WHERE verification_id = ?",
                String.class, result.verificationId())).isEqualTo("completed");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE event_type = 'verification.Verification.Completed' "
                        + "AND aggregate_id = ?", Integer.class, result.verificationId())).isEqualTo(1);
    }

    @Test
    void a_non_stale_result_is_cache_reused_not_re_called() { // INV-2
        UUID subject = UUID.randomUUID();
        VerificationResult first = verification.verifyPan(subject, "ABCDE1234F");
        VerificationResult second = verification.verifyPan(subject, "ABCDE1234F");

        assertThat(second.verificationId()).isEqualTo(first.verificationId()); // same row, no new vendor call
        assertThat(rowsFor(subject, "verify_pan")).isEqualTo(1);
    }

    @Test
    void a_stale_result_is_not_reused_and_a_fresh_verification_is_issued() { // INV-3
        UUID subject = UUID.randomUUID();
        VerificationResult first = verification.verifyPan(subject, "ABCDE1234F");
        // Age the TTL into the past, then sweep it stale.
        jdbc.update("UPDATE gate_verification SET ttl_until = now() - interval '1 day' WHERE verification_id = ?",
                first.verificationId());
        verification.markStale(first.verificationId());
        assertThat(jdbc.queryForObject("SELECT status::text FROM gate_verification WHERE verification_id = ?",
                String.class, first.verificationId())).isEqualTo("stale");

        VerificationResult fresh = verification.verifyPan(subject, "ABCDE1234F");
        assertThat(fresh.verificationId()).isNotEqualTo(first.verificationId());
        assertThat(rowsFor(subject, "verify_pan")).isEqualTo(2);
    }

    @Test
    void one_shot_apis_have_no_ttl_and_are_never_cache_reused() { // INV-3
        UUID subject = UUID.randomUUID();
        VerificationResult a = verification.verifyIrn(subject, "IRN-0001");
        VerificationResult b = verification.verifyIrn(subject, "IRN-0001");

        assertThat(a.ttlUntil()).isNull();
        assertThat(b.verificationId()).isNotEqualTo(a.verificationId()); // one-shot: fresh every time
        assertThat(rowsFor(subject, "verify_irn")).isEqualTo(2);
    }

    @Test
    void the_verbatim_payload_is_stored_only_as_a_hash() { // INV-4
        UUID subject = UUID.randomUUID();
        VerificationResult result = verification.verifyGstin(subject, "27ABCDE1234F1Z5");

        byte[] hash = jdbc.queryForObject(
                "SELECT vendor_payload_hash FROM gate_verification WHERE verification_id = ?",
                byte[].class, result.verificationId());
        assertThat(hash).hasSize(32); // SHA-256, not the raw payload
        assertThat(jdbc.queryForObject(
                "SELECT hmac_verified_at IS NOT NULL FROM gate_verification WHERE verification_id = ?",
                Boolean.class, result.verificationId())).isTrue(); // V.2 stamped at completion
    }

    @Test
    void one_shot_payload_hash_varies_with_the_request_input() { // INV-4 (hash is of the request)
        UUID subject = UUID.randomUUID();
        VerificationResult a = verification.verifyIrn(subject, "IRN-0001");
        VerificationResult b = verification.verifyIrn(subject, "IRN-0002");

        byte[] hashA = jdbc.queryForObject(
                "SELECT vendor_payload_hash FROM gate_verification WHERE verification_id = ?", byte[].class, a.verificationId());
        byte[] hashB = jdbc.queryForObject(
                "SELECT vendor_payload_hash FROM gate_verification WHERE verification_id = ?", byte[].class, b.verificationId());
        assertThat(hashA).isNotEqualTo(hashB);
    }

    private int rowsFor(UUID subject, String api) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM gate_verification WHERE subject_id = ? AND api_name = ?::verification_api_enum",
                Integer.class, subject, api);
    }
}
