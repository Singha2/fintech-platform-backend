package com.arthvritt.platform.dashboard;

import com.arthvritt.platform.auth.AuthSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BE-12 (UI_INTEGRATION_BACKEND_SPEC) — the S2 admin dashboard: per-role work-queue counts + headline stats.
 *
 * <p>This read spans several bounded contexts (supplier / investor / listing / settlement), so it lives in a
 * neutral {@code dashboard} package rather than any one BC — and reads only via raw SQL (no Java-level cross-BC
 * coupling, so ArchUnit ARCH.1 stays green). Per the spec's design-fit note it is computed live from the write
 * tables (COUNT / SUM over statuses); the "queue as a projection" optimisation is deferred (no projection table
 * for the pilot). Every queue's {@code (status, role)} pairing is grounded in the corresponding command's role
 * gate in {@code API_CATALOGUE.md} — the queue counts exactly what that role must action next.
 */
@RestController
public class AdminDashboardController {

    /** A pending-work queue: its stable {@code name}, the {@code role} that actions it, and the COUNT that fills it. */
    private record Queue(String name, String role, String countSql) {
    }

    // Each COUNT is over a hard-coded status literal (never user input) — the only dynamic input is the ?role=
    // filter, which is compared in Java (below), never interpolated into SQL.
    private static final List<Queue> QUEUES = List.of(
            new Queue("supplier_kyc_review", "compliance_reviewer",
                    "SELECT count(*) FROM sup_account WHERE status = 'kyc_submitted'"),
            new Queue("supplier_credit_review", "credit_reviewer",
                    "SELECT count(*) FROM sup_account WHERE status = 'kyc_approved'"),
            new Queue("investor_kyc_review", "compliance_reviewer",
                    "SELECT count(*) FROM inv_account WHERE status = 'kyc_submitted'"),
            new Queue("listing_ops_checks", "ops_executive",
                    "SELECT count(*) FROM deal_listing WHERE status = 'operational_checks_in_progress'"),
            new Queue("listing_golive_review", "treasury_and_settlement",
                    "SELECT count(*) FROM deal_listing WHERE status = 'ready_for_review'"),
            new Queue("disbursement_approval", "treasury_and_settlement",
                    "SELECT count(*) FROM cash_payout_instruction WHERE kind = 'disbursement' AND status = 'drafted'"),
            new Queue("distribution_approval", "treasury_and_settlement",
                    "SELECT count(*) FROM cash_payout_instruction WHERE kind = 'distribution' AND status = 'drafted'"));

    private final JdbcTemplate jdbc;

    public AdminDashboardController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code GET /admin/work-queues?role=} — the per-role pending-action counts driving the S2 landing queues.
     * Optional {@code role} filter narrows to one role's queues; an unknown role simply yields an empty list.
     */
    @GetMapping("/admin/work-queues")
    public List<Map<String, Object>> workQueues(@AuthenticationPrincipal AuthSession session,
                                                @RequestParam(name = "role", required = false) String role) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Queue q : QUEUES) {
            if (role != null && !role.isBlank() && !q.role().equals(role)) {
                continue;
            }
            Long count = jdbc.queryForObject(q.countSql(), Long.class);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("queue", q.name());
            row.put("role", q.role());
            row.put("count", count == null ? 0L : count);
            out.add(row);
        }
        return out;
    }

    /**
     * {@code GET /admin/stats} — the S2 headline tiles. {@code total_deployed_paise} is the capital actually put
     * out to suppliers: the gross of every disbursement instruction past {@code drafted} (i.e. approved onward),
     * excluding {@code failed}. {@code active_listings} is the in-flight (post-go-live, pre-terminal) set.
     */
    @GetMapping("/admin/stats")
    public Map<String, Object> stats(@AuthenticationPrincipal AuthSession session) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active_listings", count("SELECT count(*) FROM deal_listing WHERE status IN "
                + "('live', 'fully_funded', 'disbursed', 'in_repayment', 'matured_payment_received')"));
        m.put("total_deployed_paise", count("SELECT COALESCE(SUM(gross_amount), 0)::bigint "
                + "FROM cash_payout_instruction WHERE kind = 'disbursement' AND status NOT IN ('drafted', 'failed')"));
        m.put("investors_active", count("SELECT count(*) FROM inv_account WHERE status = 'active'"));
        m.put("suppliers_active", count("SELECT count(*) FROM sup_account WHERE status = 'active'"));
        m.put("pending_disbursements", count("SELECT count(*) FROM cash_payout_instruction "
                + "WHERE kind = 'disbursement' AND status = 'drafted'"));
        return m;
    }

    private long count(String sql) {
        Long v = jdbc.queryForObject(sql, Long.class);
        return v == null ? 0L : v;
    }
}
