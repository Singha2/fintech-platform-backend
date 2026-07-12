package com.arthvritt.platform.compliance;

import com.arthvritt.platform.auth.ActionSensitivity;
import com.arthvritt.platform.auth.AuthSession;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.infrastructure.web.CommandResponse;
import com.arthvritt.platform.infrastructure.web.CommandResponseAssembler;
import com.arthvritt.platform.infrastructure.web.RequestBodies;
import com.arthvritt.platform.shared.error.ValidationException;
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
 * BC11 layer-4 HTTP surface (M20, DL-BE-073) — attaches an already-{@code stored} M18 {@code document_id}
 * to a {@code comp_kyc_file}, typed by {@code doc_kind}, and serves the (advisory) coverage view. Attach
 * and supersede are gateway commands (idempotent on {@code X-Command-Id}, MFA-fresh, SoD, audited) —
 * mirrors {@link com.arthvritt.platform.listing.InvoiceDocumentController}; unversioned (no target
 * aggregate_version to guard; the link table's own constraints do the guarding).
 */
@RestController
@RequestMapping("/kyc/{kycFileId}/documents")
public class KycDocumentController {

    private static final String CONTEXT = "compliance";
    private static final String AGGREGATE_TYPE = "KycFile";

    private final KycDocumentService documents;
    private final CommandResponseAssembler responses;

    public KycDocumentController(KycDocumentService documents, CommandResponseAssembler responses) {
        this.documents = documents;
        this.responses = responses;
    }

    @PostMapping
    public CommandResponse attach(@AuthenticationPrincipal AuthSession session, @PathVariable UUID kycFileId,
                                  @RequestHeader("X-Command-Id") UUID commandId,
                                  @RequestBody Map<String, Object> body) {
        UUID documentId = uuid(RequestBodies.requiredString(body, "document_id"), "document_id");
        String docKind = RequestBodies.requiredString(body, "doc_kind");
        CommandRequest request = command(session, commandId, kycFileId, ".KycFile.AttachDocument");
        return responses.from(documents.attach(request, kycFileId, documentId, docKind));
    }

    @PutMapping("/{kycDocumentId}")
    public CommandResponse supersede(@AuthenticationPrincipal AuthSession session, @PathVariable UUID kycFileId,
                                     @PathVariable UUID kycDocumentId, @RequestHeader("X-Command-Id") UUID commandId,
                                     @RequestBody Map<String, Object> body) {
        UUID newDocumentId = uuid(RequestBodies.requiredString(body, "new_document_id"), "new_document_id");
        CommandRequest request = command(session, commandId, kycFileId, ".KycFile.SupersedeDocument");
        return responses.from(documents.supersede(request, kycFileId, kycDocumentId, newDocumentId));
    }

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal AuthSession session,
                                          @PathVariable UUID kycFileId) {
        return documents.list(kycFileId);
    }

    @GetMapping("/coverage")
    public Map<String, Boolean> coverage(@AuthenticationPrincipal AuthSession session,
                                         @PathVariable UUID kycFileId) {
        return documents.coverage(kycFileId);
    }

    private CommandRequest command(AuthSession session, UUID commandId, UUID kycFileId, String name) {
        return new CommandRequest(session, commandId, CONTEXT, CONTEXT + name, AGGREGATE_TYPE, kycFileId,
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
