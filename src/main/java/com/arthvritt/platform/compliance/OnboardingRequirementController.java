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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BC11 layer-4 HTTP surface (M20, DL-BE-073) — the runtime-editable suggested-document-kind list
 * (OD.4) consumed by {@link KycDocumentController#coverage}. {@code setRequirement} is a gateway
 * command (idempotent-create on {@code X-Command-Id}); reads are plain bearer.
 */
@RestController
@RequestMapping("/onboarding-doc-requirements")
public class OnboardingRequirementController {

    private static final String CONTEXT = "compliance";
    private static final String AGGREGATE_TYPE = "OnboardingRequirement";

    private final KycDocumentService documents;
    private final CommandResponseAssembler responses;

    public OnboardingRequirementController(KycDocumentService documents, CommandResponseAssembler responses) {
        this.documents = documents;
        this.responses = responses;
    }

    @GetMapping
    public List<Map<String, Object>> requirements(@AuthenticationPrincipal AuthSession session,
                                                  @RequestParam("subject_type") String subjectType) {
        return documents.requirements(subjectType);
    }

    @PostMapping
    public CommandResponse setRequirement(@AuthenticationPrincipal AuthSession session,
                                          @RequestHeader("X-Command-Id") UUID commandId,
                                          @RequestBody Map<String, Object> body) {
        String subjectType = RequestBodies.requiredString(body, "subject_type");
        String docKind = RequestBodies.requiredString(body, "doc_kind");
        boolean active = requiredBoolean(body, "active");
        UUID aggregateId = RequestBodies.deriveAggregateId("onboarding_req", commandId, subjectType + ":" + docKind);
        CommandRequest request = new CommandRequest(session, commandId, CONTEXT, CONTEXT + ".OnboardingRequirement.Set",
                AGGREGATE_TYPE, aggregateId, 0, "admin_user", ActionSensitivity.SENSITIVE);
        return responses.from(documents.setRequirement(request, subjectType, docKind, active));
    }

    private static boolean requiredBoolean(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (!(value instanceof Boolean bool)) {
            throw new ValidationException("missing required boolean field: " + field);
        }
        return bool;
    }
}
