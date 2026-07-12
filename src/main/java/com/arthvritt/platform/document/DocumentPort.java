package com.arthvritt.platform.document;

import java.util.Optional;
import java.util.UUID;

/**
 * BC16 Documents — the in-process port consuming contexts depend on (M18, DL-BE-070/072/075).
 * The generic, unified document service: every document is one {@code sys_document} row keyed by a
 * surrogate {@code document_id}, stored/retrieved the same way regardless of {@code kind}. This port
 * exposes the <b>M18a store core</b> (server-generated + read paths); the two-phase upload API
 * ({@code initiate}/{@code finalize}) is added in M18b.
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
}
