package com.arthvritt.platform.dev;

import com.arthvritt.platform.investor.InvestorQueryPort;
import com.arthvritt.platform.notification.StubNotifier;
import com.arthvritt.platform.shared.error.NotFoundException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <b>Dev profile only</b> local-testing affordances (never wired in prod — {@link Profile}). Two helpers:
 * <ul>
 *   <li>{@code GET /dev/last-otp?email=...} — the SMS-OTP the {@link StubNotifier} "sent" to that identity,
 *       so the password → OTP login is scriptable (the code is otherwise only hashed in the DB).</li>
 *   <li>{@code GET /dev/seed-info} — the ids of the {@link DevDataSeeder}-seeded supplier / buyer / investor,
 *       so the golden-path requests can reference them.</li>
 *   <li>{@code POST /dev/seed-listing} — fast-forwards a fresh listing to a money-flow {@code stage}
 *       (via {@link DevListingSeeder}) so the UI's write commands (S12 subscribe, S6 disburse, S7 mature/
 *       distribute) can be driven end-to-end without hand-running the ~20-command real pipeline.</li>
 * </ul>
 * These routes are permitted without a bearer (SecurityConfig) — they only exist under the dev profile.
 */
@RestController
@Profile("dev")
public class DevController {

    private final JdbcTemplate jdbc;
    private final StubNotifier notifier;
    private final InvestorQueryPort investors;
    private final DevListingSeeder listingSeeder;

    public DevController(JdbcTemplate jdbc, StubNotifier notifier, InvestorQueryPort investors,
                         DevListingSeeder listingSeeder) {
        this.jdbc = jdbc;
        this.notifier = notifier;
        this.investors = investors;
        this.listingSeeder = listingSeeder;
    }

    @GetMapping("/dev/last-otp")
    public Map<String, String> lastOtp(@RequestParam String email) {
        UUID identityId = jdbc.query("SELECT identity_id FROM auth_identity WHERE email = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, email);
        if (identityId == null) {
            throw new NotFoundException("no identity for email: " + email);
        }
        String code = notifier.lastCodeFor(identityId)
                .orElseThrow(() -> new NotFoundException("no OTP has been sent to " + email
                        + " — call POST /auth/login/password first"));
        return Map.of("email", email, "code", code);
    }

    @GetMapping("/dev/seed-info")
    public Map<String, Object> seedInfo() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("supplier_id", idByQuery("SELECT supplier_id FROM sup_account WHERE legal_name = 'DEV Supplier'"));
        out.put("buyer_id", idByQuery("SELECT buyer_id FROM buyer_account WHERE legal_name = 'DEV Buyer'"));
        // M10-D P0: repoint through the shared identity_id -> investor_id resolver (not an ad-hoc join here).
        UUID investorIdentityId = jdbc.query(
                "SELECT identity_id FROM auth_identity WHERE email = 'investor@dev.local'",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null);
        out.put("investor_id", investorIdentityId == null ? null
                : investors.investorIdForIdentity(investorIdentityId).map(UUID::toString).orElse(null));
        out.put("admins_password", DevDataSeeder.PASSWORD);
        return out;
    }

    /**
     * Seeds a fresh listing fast-forwarded to {@code stage} and returns the ids to drive the UI write under
     * test. Body (all optional): {@code {stage, rate_bps, amount_paise, maker}}. Dev-profile only.
     */
    @PostMapping("/dev/seed-listing")
    public Map<String, Object> seedListing(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String stage = asString(b.get("stage"));
        Integer rateBps = asInt(b.get("rate_bps"));
        Long amountPaise = asLong(b.get("amount_paise"));
        String maker = asString(b.get("maker"));
        return listingSeeder.seed(stage, rateBps, amountPaise, maker);
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Integer asInt(Object v) {
        return v == null ? null : ((Number) v).intValue();
    }

    private static Long asLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private String idByQuery(String sql) {
        UUID id = jdbc.query(sql, rs -> rs.next() ? rs.getObject(1, UUID.class) : null);
        return id == null ? null : id.toString();
    }
}
