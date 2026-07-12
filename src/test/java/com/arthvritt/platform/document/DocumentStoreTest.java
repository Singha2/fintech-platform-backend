package com.arthvritt.platform.document;

import com.arthvritt.platform.AbstractIntegrationTest;
import com.arthvritt.platform.shared.error.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M18a · Documents store core — RED invariant tests (see docs/modules/M18-documents.md §7, DL-BE-075).
 *
 * <p>Proves the <b>unified</b> store: {@code DocumentPort.storeGenerated/resolve/retrieve} over the new
 * {@code sys_document} + {@code sys_document_blob} tables (V10) — the "how a document is handled" layer that
 * is identical for every {@code kind}. Written test-first: they fail until the implementer adds the V10
 * migration and the real {@link DocumentService} store logic (currently a throwing scaffold).
 *
 * <p>Load-bearing invariants asserted here:
 * <ul>
 *   <li><b>round-trip</b> — storeGenerated writes a stored handle + its bytes; retrieve returns them exactly.</li>
 *   <li><b>HASH-1</b> — {@code doc_hash = SHA-256(bytes)}; identical content → same hash, distinct handles.</li>
 *   <li><b>STATUS-1</b> (DB CHECK) — a {@code stored} row must carry {@code byte_size} + {@code doc_hash}.</li>
 *   <li><b>DO.2 / control #5</b> — exactly one audit envelope, carrying no raw bytes.</li>
 *   <li><b>isolation</b> — M18 never writes the legacy {@code sys_document_object} (Form 16A stays untouched → M18d).</li>
 * </ul>
 *
 * <p>Not @Transactional: each test isolates via unique random content, mirroring {@code NotificationAclTest}.
 */
class DocumentStoreTest extends AbstractIntegrationTest {

    private static final String STORED_HAS_BYTES_CHECK = "sys_document_stored_has_bytes";

    @Autowired private DocumentPort documents;
    @Autowired private JdbcTemplate jdbc;

    // --- round-trip: store core writes the handle + the blob, and reads back the exact bytes -------------

    @Test
    void storeGenerated_writesStoredHandleAndBlob_andRetrieveReturnsExactBytes() {
        byte[] bytes = uniqueContent("round-trip");

        DocMeta meta = documents.storeGenerated(
                bytes, "form_16a", "text/plain; charset=utf-8", "bc12_tax", "TaxYearProfile:X:2025");

        // returned metadata
        assertThat(meta.documentId()).isNotNull();
        assertThat(meta.kind()).isEqualTo("form_16a");
        assertThat(meta.status()).isEqualTo("stored");
        assertThat(meta.byteSize()).isEqualTo(bytes.length);
        assertThat(meta.docHash()).isEqualTo(sha256(bytes));

        // persisted sys_document row
        assertThat(jdbc.queryForObject(
                "SELECT status::text FROM sys_document WHERE document_id = ?", String.class, meta.documentId()))
                .isEqualTo("stored");
        assertThat(jdbc.queryForObject(
                "SELECT byte_size FROM sys_document WHERE document_id = ?", Long.class, meta.documentId()))
                .isEqualTo((long) bytes.length);
        assertThat(jdbc.queryForObject(
                "SELECT owner_ref FROM sys_document WHERE document_id = ?", String.class, meta.documentId()))
                .isEqualTo("TaxYearProfile:X:2025");
        assertThat(jdbc.queryForObject(
                "SELECT doc_hash FROM sys_document WHERE document_id = ?", byte[].class, meta.documentId()))
                .isEqualTo(sha256(bytes));

        // persisted blob
        assertThat(jdbc.queryForObject(
                "SELECT content_bytes FROM sys_document_blob WHERE document_id = ?", byte[].class, meta.documentId()))
                .isEqualTo(bytes);

        // retrieve + resolve
        assertThat(documents.retrieve(meta.documentId())).isEqualTo(bytes);
        Optional<DocMeta> resolved = documents.resolve(meta.documentId());
        assertThat(resolved).isPresent();
        assertThat(resolved.get().documentId()).isEqualTo(meta.documentId());
        assertThat(resolved.get().status()).isEqualTo("stored");
    }

    // --- HASH-1: content-addressed. Same bytes → same hash, but distinct handles (no auto-dedup) ---------

    @Test
    void storeGenerated_isContentAddressed_identicalBytesShareHash_distinctHandles() {
        byte[] bytes = uniqueContent("content-address");

        DocMeta a = documents.storeGenerated(bytes, "invoice", "application/pdf", "bc1_listing", null);
        DocMeta b = documents.storeGenerated(bytes, "invoice", "application/pdf", "bc1_listing", null);

        assertThat(a.documentId()).isNotEqualTo(b.documentId());   // distinct handles
        assertThat(a.docHash()).isEqualTo(b.docHash());            // same content address
        assertThat(a.docHash()).isEqualTo(sha256(bytes));
        assertThat(documents.retrieve(a.documentId())).isEqualTo(bytes);
        assertThat(documents.retrieve(b.documentId())).isEqualTo(bytes);
    }

    // --- retrieve of an unknown id is a clean NotFound, not a 500 ----------------------------------------

    @Test
    void retrieve_unknownDocument_throwsNotFound() {
        assertThatThrownBy(() -> documents.retrieve(UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    // --- DO.2 / control #5: exactly one audit envelope, and it never carries the raw bytes ---------------

    @Test
    void storeGenerated_emitsOneStoredAuditEvent_withoutRawBytes() {
        String marker = "MARKER-" + UUID.randomUUID();
        byte[] bytes = ("form16a-body::" + marker).getBytes(StandardCharsets.UTF_8);

        DocMeta meta = documents.storeGenerated(bytes, "form_16a", "text/plain", "bc12_tax", "TaxYearProfile:Y:2025");

        assertThat(auditCount("documents.Document.Stored", meta.documentId())).isEqualTo(1);
        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM sys_audit_event WHERE aggregate_id = ? AND event_type = ?",
                String.class, meta.documentId(), "documents.Document.Stored");
        assertThat(payload).doesNotContain(marker);   // binary never inlined into the envelope (DO.2)
    }

    // --- isolation: the unified store never writes the legacy Form-16A registry (converges only at M18d) --

    @Test
    void storeGenerated_doesNotWriteLegacySysDocumentObject() {
        byte[] bytes = uniqueContent("isolation");

        DocMeta meta = documents.storeGenerated(bytes, "kyc", "application/pdf", "bc11_compliance", null);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_document_object WHERE doc_hash = ?", Integer.class, meta.docHash()))
                .isZero();
    }

    // --- STATUS-1 (DB CHECK): a 'stored' row must carry its bytes; a 'pending_upload' row need not --------

    @Test
    void schema_storedRowWithoutByteSizeOrHash_isRejected() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO sys_document(document_id,kind,content_type,status,created_by) "
                        + "VALUES (?,?,?,CAST(? AS sys_document_status),?)",
                UUID.randomUUID(), "invoice", "application/pdf", "stored", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(STORED_HAS_BYTES_CHECK);
    }

    @Test
    void schema_pendingUploadRowWithoutBytes_isAccepted() {
        assertThatCode(() -> jdbc.update(
                "INSERT INTO sys_document(document_id,kind,content_type,status,created_by) "
                        + "VALUES (?,?,?,CAST(? AS sys_document_status),?)",
                UUID.randomUUID(), "invoice", "application/pdf", "pending_upload", UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    // --- helpers ----------------------------------------------------------------------------------------

    private int auditCount(String eventType, UUID aggregateId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, eventType, aggregateId);
    }

    /** Unique per call so tests stay independent in the shared (non-transactional) container. */
    private static byte[] uniqueContent(String tag) {
        return ("doc::" + tag + "::" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
