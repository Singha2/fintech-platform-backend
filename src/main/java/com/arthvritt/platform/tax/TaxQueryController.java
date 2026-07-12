package com.arthvritt.platform.tax;

import com.arthvritt.platform.auth.AuthSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BC12 Tax read surface (M16): an investor's TDS deduction ledger and issued statements. Plain reads — any
 * authenticated admin may view them, mirroring {@link com.arthvritt.platform.settlement.DisbursementController#get}.
 */
@RestController
public class TaxQueryController {

    private final JdbcTemplate jdbc;

    public TaxQueryController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/investors/{investorId}/tax/deductions")
    public List<Map<String, Object>> deductions(@AuthenticationPrincipal AuthSession session,
                                                @PathVariable UUID investorId,
                                                @RequestParam(name = "fy", required = false) String fyCode) {
        if (fyCode != null && !fyCode.isBlank()) {
            return jdbc.query("SELECT listing_id, fy_code, gross_paise, tds_amount_paise, fee_paise, net_paise, "
                            + "challan_ref FROM tax_tds_deduction WHERE investor_id = ? AND fy_code = ? "
                            + "ORDER BY listing_id",
                    (rs, n) -> deductionRow(rs), investorId, fyCode);
        }
        return jdbc.query("SELECT listing_id, fy_code, gross_paise, tds_amount_paise, fee_paise, net_paise, "
                        + "challan_ref FROM tax_tds_deduction WHERE investor_id = ? ORDER BY fy_code, listing_id",
                (rs, n) -> deductionRow(rs), investorId);
    }

    @GetMapping("/investors/{investorId}/tax/statements")
    public List<Map<String, Object>> statements(@AuthenticationPrincipal AuthSession session,
                                                @PathVariable UUID investorId) {
        return jdbc.query("SELECT period, kind::text AS kind, generated_at, doc_hash "
                        + "FROM tax_investor_statement WHERE investor_id = ? ORDER BY period, kind",
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("period", rs.getString("period"));
                    m.put("kind", rs.getString("kind"));
                    m.put("generated_at", rs.getObject("generated_at", java.time.OffsetDateTime.class));
                    m.put("doc_hash", java.util.HexFormat.of().formatHex(rs.getBytes("doc_hash")));
                    return m;
                },
                investorId);
    }

    private static Map<String, Object> deductionRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("listing_id", rs.getObject("listing_id", UUID.class).toString());
        m.put("fy_code", rs.getString("fy_code"));
        m.put("gross_paise", rs.getLong("gross_paise"));
        m.put("tds_amount_paise", rs.getLong("tds_amount_paise"));
        m.put("fee_paise", rs.getLong("fee_paise"));
        m.put("net_paise", rs.getLong("net_paise"));
        m.put("challan_ref", rs.getString("challan_ref"));
        return m;
    }
}
