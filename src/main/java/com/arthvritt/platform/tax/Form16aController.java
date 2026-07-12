package com.arthvritt.platform.tax;

import com.arthvritt.platform.auth.ActionSensitivity;
import com.arthvritt.platform.auth.AuthSession;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.infrastructure.web.CommandResponse;
import com.arthvritt.platform.infrastructure.web.CommandResponseAssembler;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * BC12 Form 16A HTTP surface (M16). One Compliance command — {@code issue} — mirrors
 * {@link com.arthvritt.platform.settlement.MaturityController} (a single, non-maker-checker gateway
 * command). The aggregate id is deterministic from {@code (investorId, fyCode)}, so a replayed issue on
 * the same profile resolves to the same aggregate regardless of the command_id used. {@code download}
 * is a plain read: any authenticated admin may re-render the stored certificate.
 */
@RestController
public class Form16aController {

    private static final String CONTEXT = "tax";
    private static final String AGGREGATE_TYPE = "TaxYearProfile";

    private final Form16aService form16a;
    private final CommandResponseAssembler responses;

    public Form16aController(Form16aService form16a, CommandResponseAssembler responses) {
        this.form16a = form16a;
        this.responses = responses;
    }

    @PostMapping("/investors/{investorId}/tax/form-16a/{fyCode}/issue")
    public CommandResponse issue(@AuthenticationPrincipal AuthSession session, @PathVariable UUID investorId,
                                 @PathVariable String fyCode, @RequestHeader("X-Command-Id") UUID commandId) {
        UUID aggregateId = UUID.nameUUIDFromBytes(
                ("form16a:" + investorId + ":" + fyCode).getBytes(StandardCharsets.UTF_8));
        CommandRequest request = new CommandRequest(session, commandId, CONTEXT,
                CONTEXT + ".TaxYearProfile.Form16aIssue", AGGREGATE_TYPE, aggregateId, 0, "admin_user",
                ActionSensitivity.SENSITIVE);
        return responses.from(form16a.issue(request, investorId, fyCode));
    }

    @GetMapping("/investors/{investorId}/tax/form-16a/{fyCode}")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal AuthSession session,
                                           @PathVariable UUID investorId, @PathVariable String fyCode) {
        byte[] bytes = form16a.download(investorId, fyCode);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/plain;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("Form16A-" + fyCode + ".txt").build().toString())
                .body(bytes);
    }
}
