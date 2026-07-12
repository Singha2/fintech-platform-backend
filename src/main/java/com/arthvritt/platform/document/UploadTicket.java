package com.arthvritt.platform.document;

import java.util.UUID;

/**
 * BC16 Documents — the result of {@link DocumentPort#initiate} (M18b, DL-BE-075): the freshly (or
 * idempotently) minted document handle, plus the URL the caller {@code PUT}s the raw bytes to. Local
 * backend: {@code uploadUrl} is our own {@code /documents/{id}/content} endpoint (the caller already
 * holds a session bearer — I3). Production (M18c) swaps this for a presigned direct-to-GCS URL; the
 * shape is unchanged.
 */
public record UploadTicket(UUID documentId, String uploadUrl) {
}
