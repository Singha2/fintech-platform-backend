package com.arthvritt.platform.document;

import java.util.UUID;

/**
 * BC16 Documents — the swappable byte-storage seam (M18, DL-BE-075). {@link DocumentService} addresses
 * the universal {@code sys_document} handle; this port owns only the raw bytes behind it. Local backend
 * is {@link DbTableDocumentStore} ({@code sys_document_blob}); production swaps in a GCS adapter
 * (direct-to-blob, presigned URLs) at the M18c / Production gate — this port's shape is unchanged.
 */
public interface DocumentStorePort {

    /** Writes the raw bytes for {@code documentId}. */
    void put(UUID documentId, byte[] bytes);

    /**
     * The raw bytes for {@code documentId}.
     *
     * @throws com.arthvritt.platform.shared.error.NotFoundException if no bytes are stored for this id
     */
    byte[] get(UUID documentId);

    /** Whether bytes are stored for {@code documentId}. */
    boolean exists(UUID documentId);
}
