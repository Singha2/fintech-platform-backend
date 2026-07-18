package com.arthvritt.platform.listing;

import com.arthvritt.platform.auth.ActionSensitivity;
import com.arthvritt.platform.auth.AuthSession;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.infrastructure.web.CommandResponse;
import com.arthvritt.platform.infrastructure.web.CommandResponseAssembler;
import com.arthvritt.platform.infrastructure.web.RequestBodies;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BC1 layer-4 HTTP surface (M19, DL-BE-071) — attaches an already-{@code stored} M18 {@code document_id}
 * to a listing's invoice, lists the artifact set, and serves the download. The upload itself is the
 * generic M18 {@code /documents} flow; these endpoints only bind the resulting handle onto the invoice.
 * Attach/replace are gateway commands (idempotent on {@code X-Command-Id}, MFA-fresh, SoD, audited) —
 * mirroring {@link ListingController}'s conventions, but — like {@code ListingController.create} —
 * unversioned (no target aggregate_version to guard; the link table's own constraints do the guarding).
 */
@RestController
@RequestMapping("/listings/{listingId}/invoice-documents")
public class InvoiceDocumentController {

    private static final String CONTEXT = "listing";
    private static final String AGGREGATE_TYPE = "InvoiceArtifact";

    private final InvoiceDocumentService artifacts;
    private final CommandResponseAssembler responses;

    public InvoiceDocumentController(InvoiceDocumentService artifacts, CommandResponseAssembler responses) {
        this.artifacts = artifacts;
        this.responses = responses;
    }

    @PostMapping
    public CommandResponse attach(@AuthenticationPrincipal AuthSession session, @PathVariable UUID listingId,
                                  @RequestHeader("X-Command-Id") UUID commandId,
                                  @RequestBody Map<String, Object> body) {
        UUID documentId = uuid(RequestBodies.requiredString(body, "document_id"), "document_id");
        CommandRequest request = command(session, commandId, documentId, ".InvoiceArtifact.Attach");
        return responses.from(artifacts.attach(request, listingId, documentId));
    }

    @PutMapping("/{documentId}")
    public CommandResponse replace(@AuthenticationPrincipal AuthSession session, @PathVariable UUID listingId,
                                   @PathVariable UUID documentId, @RequestHeader("X-Command-Id") UUID commandId,
                                   @RequestBody Map<String, Object> body) {
        UUID newDocumentId = uuid(RequestBodies.requiredString(body, "new_document_id"), "new_document_id");
        CommandRequest request = command(session, commandId, documentId, ".InvoiceArtifact.Supersede");
        return responses.from(artifacts.replace(request, listingId, documentId, newDocumentId));
    }

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal AuthSession session, @PathVariable UUID listingId) {
        return artifacts.list(listingId);
    }

    @GetMapping("/{documentId}/content")
    public ResponseEntity<byte[]> content(@AuthenticationPrincipal AuthSession session,
                                          @PathVariable UUID listingId, @PathVariable UUID documentId) {
        byte[] bytes = artifacts.download(session, listingId, documentId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(bytes);
    }

    private CommandRequest command(AuthSession session, UUID commandId, UUID aggregateId, String name) {
        return new CommandRequest(session, commandId, CONTEXT, CONTEXT + name, AGGREGATE_TYPE, aggregateId,
                0, "admin_user", ActionSensitivity.SENSITIVE);
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("field '" + field + "' is not a valid id");
        }
    }
}
