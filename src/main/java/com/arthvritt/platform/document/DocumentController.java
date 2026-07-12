package com.arthvritt.platform.document;

import com.arthvritt.platform.auth.AuthSession;
import com.arthvritt.platform.infrastructure.web.RequestBodies;
import com.arthvritt.platform.shared.error.NotFoundException;
import com.arthvritt.platform.shared.error.ValidationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BC16 Documents — the generic two-phase upload surface (M18b, DL-BE-072/075 I2/I3/I4). An operational
 * path, <b>not</b> a {@link com.arthvritt.platform.command.CommandGateway} command (I2): the 3-step
 * upload doesn't fit {@code CommandRequest}'s single-aggregate-mutation shape, so this mirrors the
 * webhook idiom ({@code SettlementService.reconcile}) — plain session-authenticated endpoints over
 * {@link DocumentPort}, which itself does the {@code jdbc} + {@code AuditLog.append}. Any authenticated
 * admin may call these (no role gate, no MFA-freshness check) — custody/transport infrastructure, not a
 * domain state change (M18 §6); the consumer's own <b>attach</b> command carries the five non-negotiables.
 */
@RestController
@RequestMapping("/documents")
public class DocumentController {

    private final DocumentPort documents;

    public DocumentController(DocumentPort documents) {
        this.documents = documents;
    }

    /**
     * Initiate — {@code document_id} is derived deterministically from {@code X-Command-Id} (+ body), so
     * a replay resolves to the same handle rather than minting a duplicate row (idempotent, #4).
     */
    @PostMapping
    public Map<String, Object> initiate(@AuthenticationPrincipal AuthSession session,
                                        @RequestHeader("X-Command-Id") UUID commandId,
                                        @RequestBody Map<String, Object> body) {
        String kind = RequestBodies.requiredString(body, "kind");
        String contentType = RequestBodies.requiredString(body, "content_type");
        long declaredSize = requiredLong(body, "declared_size");
        UUID documentId = RequestBodies.deriveAggregateId("document", commandId,
                kind + ":" + contentType + ":" + declaredSize);

        UploadTicket ticket = documents.initiate(documentId, kind, contentType, declaredSize, session.identityId());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("document_id", ticket.documentId().toString());
        response.put("upload_url", ticket.uploadUrl());
        return response;
    }

    /**
     * Upload — raw {@code application/pdf} body (I4); a mismatched content-type never reaches this
     * method (Spring 415s on the {@code consumes} mismatch). The size cap is enforced in the service
     * ({@code documents.max-upload-bytes}) before any bytes are persisted.
     */
    @PutMapping(value = "/{id}/content", consumes = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<Void> upload(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id,
                                       @RequestBody byte[] bytes) {
        documents.uploadContent(id, bytes);
        return ResponseEntity.ok().build();
    }

    /** Finalize — idempotent on {@code document_id}; a second call on an already-stored document is a no-op. */
    @PostMapping("/{id}/finalize")
    public Map<String, Object> finalize(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id) {
        DocMeta meta = documents.finalizeUpload(id, session.identityId());
        return metaBody(meta);
    }

    /** Metadata only — no bytes. */
    @GetMapping("/{id}")
    public Map<String, Object> resolve(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id) {
        DocMeta meta = documents.resolve(id).orElseThrow(() -> new NotFoundException("document not found: " + id));
        return metaBody(meta);
    }

    /** The raw bytes of a stored document (STORE-1: no authorization here — the consumer is the policy point). */
    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id) {
        DocMeta meta = documents.resolve(id).orElseThrow(() -> new NotFoundException("document not found: " + id));
        byte[] bytes = documents.retrieve(id); // throws NotFoundException if not (yet) stored
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(meta.contentType())).body(bytes);
    }

    private static Map<String, Object> metaBody(DocMeta meta) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("document_id", meta.documentId().toString());
        body.put("kind", meta.kind());
        body.put("status", meta.status());
        body.put("content_type", meta.contentType());
        body.put("byte_size", meta.byteSize());
        return body;
    }

    /** {@code declared_size} is a plain byte count, not money — validated as an integral number only. */
    private static long requiredLong(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (!(value instanceof Number number)) {
            throw new ValidationException("missing required numeric field: " + field);
        }
        return number.longValue();
    }
}
