package com.arthvritt.platform.subscription;

import com.arthvritt.platform.auth.ActionSensitivity;
import com.arthvritt.platform.auth.AuthSession;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.infrastructure.web.CommandResponse;
import com.arthvritt.platform.infrastructure.web.CommandResponseAssembler;
import com.arthvritt.platform.infrastructure.web.RequestBodies;
import com.arthvritt.platform.shared.error.NotFoundException;
import com.arthvritt.platform.shared.error.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * BC2 subscription HTTP surface (WS-5). {@code commit} is an ops-on-behalf command (investor login is
 * M11-full) routed through {@code CommandGateway} via {@link SubscriptionService}; the subscription id is
 * derived from {@code (command_id, listing, investor)} so a replay is stable and the one-per-(listing,
 * investor) rule (S.6) holds. The inflow that confirms a subscription arrives via the banking webhook, not
 * here.
 */
@RestController
public class SubscriptionController {

    private static final String CONTEXT = "subscription";

    private final SubscriptionService subscriptions;
    private final CommandResponseAssembler responses;
    private final JdbcTemplate jdbc;

    public SubscriptionController(SubscriptionService subscriptions, CommandResponseAssembler responses,
                                 JdbcTemplate jdbc) {
        this.subscriptions = subscriptions;
        this.responses = responses;
        this.jdbc = jdbc;
    }

    @PostMapping("/listings/{listingId}/subscriptions/commit")
    public ResponseEntity<CommandResponse> commit(@AuthenticationPrincipal AuthSession session,
                                                  @PathVariable UUID listingId,
                                                  @RequestHeader("X-Command-Id") UUID commandId,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        UUID investorId = uuid(RequestBodies.requiredString(body, "investor_id"));
        long amount = RequestBodies.requiredPositivePaise(body, "amount_paise");
        UUID subscriptionId = RequestBodies.deriveAggregateId("subscription", commandId,
                listingId + ":" + investorId);
        CommandRequest request = new CommandRequest(session, commandId, CONTEXT, CONTEXT + ".Subscription.Commit",
                "Subscription", subscriptionId, 0, "admin_user", ActionSensitivity.SENSITIVE);
        CommandResult<UUID> result = subscriptions.commit(request, listingId, investorId, amount);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(responses.from(result));
    }

    @GetMapping("/listings/{listingId}/subscriptions/{subscriptionId}")
    public Map<String, Object> get(@AuthenticationPrincipal AuthSession session, @PathVariable UUID listingId,
                                   @PathVariable UUID subscriptionId) {
        Map<String, Object> row = jdbc.query(
                "SELECT subscription_id, status::text AS status, amount, aggregate_version FROM sub_subscription "
                        + "WHERE subscription_id = ? AND listing_id = ?",
                rs -> rs.next()
                        ? Map.<String, Object>of(
                                "subscription_id", rs.getObject("subscription_id", UUID.class).toString(),
                                "status", rs.getString("status"),
                                "amount", rs.getLong("amount"),
                                "aggregate_version", rs.getInt("aggregate_version"))
                        : null,
                subscriptionId, listingId);
        if (row == null) {
            throw new NotFoundException("subscription not found: " + subscriptionId);
        }
        return row;
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("investor_id is not a valid id");
        }
    }
}
