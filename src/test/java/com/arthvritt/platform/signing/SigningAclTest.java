package com.arthvritt.platform.signing;

import com.arthvritt.platform.AbstractIntegrationTest;
import com.arthvritt.platform.shared.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M5c invariant tests (see docs/modules/M5c-signing-acl.md §7): the Signing ACL — a real port behind a
 * deterministic stub, with the idempotent (request, doc) session and cert-only-on-completed as the
 * headline. Integration against Testcontainers.
 */
class SigningAclTest extends AbstractIntegrationTest {

    @Autowired private SignatureAclService signing;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void initiate_creates_a_session_with_a_url_and_no_cert_yet() { // INV-1, INV-2, INV-6
        UUID vsrId = Ids.newId();
        SigningPort.SignatureSession session = signing.initiateSignature(
                vsrId, UUID.randomUUID(), docHash("agreement-1"), "+91••••1234", SignMethod.AADHAAR_OTP);

        assertThat(session.vendorSessionUrl()).startsWith("https://");
        assertThat(jdbc.queryForObject("SELECT status::text FROM gate_signature_session WHERE vsr_id = ?",
                String.class, vsrId)).isEqualTo("session_initiated");
        assertThat(jdbc.queryForObject("SELECT cert_serial FROM gate_signature_session WHERE vsr_id = ?",
                String.class, vsrId)).isNull();
        assertThat(envelopes("signing.SignatureSession.Initiated", vsrId)).isEqualTo(1);
    }

    @Test
    void re_initiating_the_same_request_and_doc_is_idempotent() { // INV-1
        UUID requestId = UUID.randomUUID();
        byte[] doc = docHash("agreement-2");
        SigningPort.SignatureSession first = signing.initiateSignature(Ids.newId(), requestId, doc, "ref", SignMethod.DSC);
        SigningPort.SignatureSession again = signing.initiateSignature(Ids.newId(), requestId, doc, "ref", SignMethod.DSC);

        assertThat(again.vsrId()).isEqualTo(first.vsrId()); // the original session, no duplicate
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM gate_signature_session WHERE signature_request_id = ?", Integer.class, requestId))
                .isEqualTo(1);
    }

    @Test
    void reusing_a_vsr_id_for_a_different_signature_is_rejected() { // INV-1 (PK reuse != idempotent)
        UUID vsrId = Ids.newId();
        signing.initiateSignature(vsrId, UUID.randomUUID(), docHash("agreement-a"), "ref", SignMethod.DSC);

        assertThatThrownBy(() -> signing.initiateSignature(
                vsrId, UUID.randomUUID(), docHash("agreement-b"), "ref", SignMethod.DSC))
                .isInstanceOf(com.arthvritt.platform.shared.error.ValidationException.class);
    }

    @Test
    void complete_sets_the_cert_and_audits() { // INV-2
        UUID vsrId = Ids.newId();
        signing.initiateSignature(vsrId, UUID.randomUUID(), docHash("agreement-3"), "ref", SignMethod.AADHAAR_OTP);

        SigningPort.SignatureResult result = signing.completeSignature(vsrId);

        assertThat(result.status()).isEqualTo(SignatureSessionStatus.COMPLETED);
        assertThat(result.certSerial()).startsWith("STUBCERT");
        assertThat(jdbc.queryForObject("SELECT status::text FROM gate_signature_session WHERE vsr_id = ?",
                String.class, vsrId)).isEqualTo("completed");
        assertThat(envelopes("signing.SignatureCompleted", vsrId)).isEqualTo(1);
    }

    @Test
    void completing_twice_is_idempotent() { // INV-2
        UUID vsrId = Ids.newId();
        signing.initiateSignature(vsrId, UUID.randomUUID(), docHash("agreement-4"), "ref", SignMethod.DSC);
        SigningPort.SignatureResult first = signing.completeSignature(vsrId);
        SigningPort.SignatureResult again = signing.completeSignature(vsrId);

        assertThat(again.certSerial()).isEqualTo(first.certSerial());
        assertThat(envelopes("signing.SignatureCompleted", vsrId)).isEqualTo(1); // no second completion
    }

    @Test
    void the_db_rejects_a_cert_serial_on_a_non_completed_session() { // INV-2 (DB CHECK)
        UUID vsrId = Ids.newId();
        signing.initiateSignature(vsrId, UUID.randomUUID(), docHash("agreement-5"), "ref", SignMethod.DSC);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE gate_signature_session SET cert_serial = 'X' WHERE vsr_id = ?", vsrId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("cert_serial_only_on_completed");
    }

    private static byte[] docHash(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private int envelopes(String eventType, UUID aggregateId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, eventType, aggregateId);
    }
}
