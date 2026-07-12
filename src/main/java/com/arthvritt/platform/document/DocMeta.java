package com.arthvritt.platform.document;

import java.util.UUID;

/**
 * BC16 document metadata — the resolvable view of a {@code sys_document} row (M18, DL-BE-075).
 * A pure value object shared across the {@link DocumentPort} surface; carries no bytes.
 *
 * @param documentId surrogate UUIDv7 identity domain entities reference
 * @param kind       opaque coarse label ({@code invoice|kyc|buyer_kyb|form_16a}) — the generic layer never interprets it
 * @param status     {@code pending_upload|stored|failed}
 * @param contentType MIME type e.g. {@code application/pdf}
 * @param byteSize   size in bytes (0 while pending)
 * @param docHash    SHA-256 of the raw bytes (null while pending; set at store/finalize)
 */
public record DocMeta(UUID documentId, String kind, String status, String contentType, long byteSize, byte[] docHash) {
}
