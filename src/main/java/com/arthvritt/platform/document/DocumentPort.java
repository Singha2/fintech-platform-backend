package com.arthvritt.platform.document;

import java.util.Optional;
import java.util.UUID;

/**
 * BC16 Documents — the in-process port consuming contexts depend on (M18, DL-BE-070/072/075).
 * The generic, unified document service: every document is one {@code sys_document} row keyed by a
 * surrogate {@code document_id}, stored/retrieved the same way regardless of {@code kind}. This port
 * exposes the <b>M18a store core</b> (server-generated + read paths) plus the <b>M18b two-phase upload
 * API</b> ({@code initiate}/{@code uploadContent}/{@code finalizeUpload}) the {@code /documents} HTTP
 * surface drives.
 *
 * <p>The port performs <b>no authorization</b> on {@code retrieve} (STORE-1) — the consuming context is
 * the policy point.
 */
public interface DocumentPort {

    /**
     * One-shot store for <b>server-generated</b> bytes (e.g. the Form 16A render path, M18d) — creates a
     * {@code sys_document} row already {@code stored} plus its blob, in a single call. Content-addresses the
     * bytes ({@code doc_hash = SHA-256}); no idempotency of its own (the caller's command is idempotent).
     *
     * @return the stored document's metadata (status {@code stored}, {@code doc_hash} + {@code byte_size} set)
     */
    DocMeta storeGenerated(byte[] rawBytes, String kind, String contentType, String ownerContext, String ownerRef);

    /** Metadata for a document, or empty if unknown. No bytes. */
    Optional<DocMeta> resolve(UUID documentId);

    /**
     * The raw bytes of a {@code stored} document. Local backend streams from {@code sys_document_blob}.
     *
     * @throws com.arthvritt.platform.shared.error.NotFoundException if the document is unknown or not yet stored
     */
    byte[] retrieve(UUID documentId);

    /**
     * Step 1 of the two-phase upload (M18b): inserts the {@code pending_upload} handle at the given
     * (caller-derived, deterministic) {@code documentId}. Idempotent on {@code documentId} — a replay
     * (same {@code X-Command-Id}) is a no-op that emits no second audit envelope.
     *
     * @param documentId deterministically derived by the caller from {@code X-Command-Id} (replay-safe)
     * @param createdBy  the initiating identity (session {@code identityId}), stamped on the row + audit actor
     */
    UploadTicket initiate(UUID documentId, String kind, String contentType, long declaredSize, UUID createdBy);

    /**
     * Step 2: writes the raw bytes for a {@code pending_upload} document. Status is unchanged (still
     * {@code pending_upload}) — only {@link #finalizeUpload} computes the hash and flips to {@code stored}.
     *
     * @throws com.arthvritt.platform.shared.error.ValidationException if the content exceeds the configured
     *         size cap (I4) — the caller's content-type is validated at the HTTP edge ({@code consumes})
     * @throws com.arthvritt.platform.shared.error.NotFoundException if the document is unknown
     */
    void uploadContent(UUID documentId, byte[] bytes);

    /**
     * Step 3: verifies bytes are present, computes {@code SHA-256}, and flips {@code status='stored'}.
     * Idempotent on {@code documentId} — a second finalize on an already-stored document is a no-op
     * (returns the existing metadata, emits no second audit envelope).
     *
     * @throws com.arthvritt.platform.shared.error.NotFoundException if the document is unknown
     * @throws com.arthvritt.platform.shared.error.ValidationException if no bytes have been uploaded yet (STATUS-1)
     */
    DocMeta finalizeUpload(UUID documentId, UUID actorId);
}
