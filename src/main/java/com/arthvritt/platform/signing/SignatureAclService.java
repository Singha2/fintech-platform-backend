package com.arthvritt.platform.signing;

import com.arthvritt.platform.acl.AbstractAclService;
import com.arthvritt.platform.audit.AuditLog;
import com.arthvritt.platform.shared.error.ValidationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * BC19 Signing ACL — the <b>fixed</b> half (gate_signature_session persistence, ACL idempotency,
 * audit) on {@link AbstractAclService}. Calls the swappable {@link SigningVendorClient} for the raw
 * vendor result. Idempotency is the ACL key — the {@code vsr_id} + the {@code (signatureRequestId,
 * docHash)} pair (VS.1) — not the M4a {@code command_id} store. Signing is BC5-triggered, not a
 * maker-checker command.
 */
@Service
public class SignatureAclService extends AbstractAclService implements SigningPort {

    private final JdbcTemplate jdbc;
    private final SigningVendorClient vendorClient;

    public SignatureAclService(JdbcTemplate jdbc, SigningVendorClient vendorClient, AuditLog auditLog) {
        super(auditLog, "signing", "signing_acl", "VendorSignatureRequest");
        this.jdbc = jdbc;
        this.vendorClient = vendorClient;
    }

    @Override
    @Transactional
    public SignatureSession initiateSignature(UUID vsrId, UUID signatureRequestId, byte[] docHash,
                                              String signerRef, SignMethod signMethod) {
        requireInputs(docHash, signerRef);
        // Atomic claim. Target the (signature_request_id, doc_hash) UNIQUE specifically (VS.1): a conflict
        // there is the idempotent re-initiate (claimed==0, re-read the original). A vsr_id PK collision is
        // NOT that — it means the caller reused a vsr_id for a different signature, so it surfaces as a
        // DuplicateKeyException, translated to a clear reject (never a silent null session).
        int claimed;
        try {
            claimed = jdbc.update("INSERT INTO gate_signature_session "
                            + "(vsr_id, signature_request_id, doc_hash, signer_ref, sign_method, status) "
                            + "VALUES (?, ?, ?, ?, ?::sign_method_enum, 'session_initiated') "
                            + "ON CONFLICT (signature_request_id, doc_hash) DO NOTHING",
                    vsrId, signatureRequestId, docHash, signerRef, signMethod.wire());
        } catch (DuplicateKeyException e) {
            throw new ValidationException("vsr_id already used for a different signature session: " + vsrId);
        }
        if (claimed == 0) {
            return existingSession(signatureRequestId, docHash); // idempotent — return the original (present)
        }
        SigningVendorClient.InitiateAck ack = vendorClient.initiate(vsrId, docHash, signerRef, signMethod);
        jdbc.update("UPDATE gate_signature_session SET vendor_session_url = ? WHERE vsr_id = ?",
                ack.vendorSessionUrl(), vsrId);
        auditAclEvent(vsrId, "signing.SignatureSession.Initiated", Map.of(
                "signature_request_id", signatureRequestId.toString(), "sign_method", signMethod.wire()));
        return new SignatureSession(vsrId, ack.vendorSessionUrl());
    }

    @Override
    @Transactional
    public SignatureResult completeSignature(UUID vsrId) {
        String status = statusOf(vsrId);
        if (status == null) {
            throw new ValidationException("no signature session: " + vsrId);
        }
        if (SignatureSessionStatus.COMPLETED.wire().equals(status)) {
            return fetchSignature(vsrId); // idempotent — already completed
        }
        SigningVendorClient.CompletionAck ack = vendorClient.complete(vsrId);
        // Sets cert_serial + status together — the DB CHECK requires cert only on 'completed' (VS.3).
        int updated = jdbc.update("UPDATE gate_signature_session SET status = 'completed', cert_serial = ?, "
                        + "hmac_verified_at = now() WHERE vsr_id = ? AND status = 'session_initiated'",
                ack.certSerial(), vsrId);
        if (updated == 0) {
            return fetchSignature(vsrId); // raced to completed concurrently
        }
        auditAclEvent(vsrId, "signing.SignatureCompleted", Map.of("cert_serial", ack.certSerial()));
        return new SignatureResult(vsrId, SignatureSessionStatus.COMPLETED, ack.certSerial());
    }

    @Override
    public SignatureResult fetchSignature(UUID vsrId) {
        SignatureResult result = jdbc.query(
                "SELECT status::text AS status, cert_serial FROM gate_signature_session WHERE vsr_id = ?",
                rs -> rs.next()
                        ? new SignatureResult(vsrId, SignatureSessionStatus.fromWire(rs.getString("status")),
                        rs.getString("cert_serial"))
                        : null,
                vsrId);
        if (result == null) {
            throw new ValidationException("no signature session: " + vsrId);
        }
        return result;
    }

    private SignatureSession existingSession(UUID signatureRequestId, byte[] docHash) {
        return jdbc.query("SELECT vsr_id, vendor_session_url FROM gate_signature_session "
                        + "WHERE signature_request_id = ? AND doc_hash = ?",
                rs -> rs.next()
                        ? new SignatureSession(rs.getObject("vsr_id", UUID.class), rs.getString("vendor_session_url"))
                        : null,
                signatureRequestId, docHash);
    }

    private String statusOf(UUID vsrId) {
        return jdbc.query("SELECT status::text FROM gate_signature_session WHERE vsr_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, vsrId);
    }

    private static void requireInputs(byte[] docHash, String signerRef) {
        if (docHash == null || docHash.length == 0) {
            throw new ValidationException("docHash is required");
        }
        if (signerRef == null || signerRef.isBlank()) {
            throw new ValidationException("signerRef is required");
        }
    }
}
