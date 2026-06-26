package com.arthvritt.platform.settlement;

import com.arthvritt.platform.auth.ActionSensitivity;
import com.arthvritt.platform.auth.AuthSession;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.infrastructure.web.CommandResponse;
import com.arthvritt.platform.infrastructure.web.CommandResponseAssembler;
import com.arthvritt.platform.infrastructure.web.RequestBodies;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * BC4 maturity HTTP surface (M13). One ops command — {@code record-maturity} — records the buyer's full
 * repayment and matures the listing. Thin adapter over {@link MaturityService}/{@code CommandGateway}.
 */
@RestController
public class MaturityController {

    private static final String CONTEXT = "settlement";

    private final MaturityService maturity;
    private final CommandResponseAssembler responses;

    public MaturityController(MaturityService maturity, CommandResponseAssembler responses) {
        this.maturity = maturity;
        this.responses = responses;
    }

    @PostMapping("/listings/{listingId}/record-maturity")
    public CommandResponse recordMaturity(@AuthenticationPrincipal AuthSession session, @PathVariable UUID listingId,
                                          @RequestHeader("X-Command-Id") UUID commandId,
                                          @RequestBody Map<String, Object> body) {
        long amount = RequestBodies.requiredPositivePaise(body, "amount_paise");
        String utr = RequestBodies.requiredString(body, "utr");
        CommandRequest request = new CommandRequest(session, commandId, CONTEXT, CONTEXT + ".Listing.RecordMaturity",
                "Listing", listingId, 0, "admin_user", ActionSensitivity.SENSITIVE);
        return responses.from(maturity.recordMaturity(request, listingId, amount, utr));
    }
}
