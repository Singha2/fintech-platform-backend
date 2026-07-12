package com.arthvritt.platform.tax;

import com.arthvritt.platform.adminiam.AdminRole;
import com.arthvritt.platform.command.CommandEvent;
import com.arthvritt.platform.command.CommandGateway;
import com.arthvritt.platform.command.CommandOutcome;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.shared.error.NotFoundException;
import com.arthvritt.platform.shared.error.ValidationException;
import com.arthvritt.platform.tax.Form16aRenderer.Form16aData;
import com.arthvritt.platform.tax.Form16aRenderer.Form16aLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BC12 Tax — Form 16A issuance (M16). A single Compliance command — NOT maker-checker (mirrors
 * {@link com.arthvritt.platform.settlement.MaturityService}): renders the investor's annual TDS
 * certificate for a financial year from the frozen {@code tax_year_profile} cumulatives + the FY's
 * {@code tax_tds_deduction} lines, registers the document (BC16, idempotent on {@code doc_hash}), and
 * stamps the profile issued. Re-issue on an already-issued FY is rejected — a correction needs a distinct
 * flow, never a silent overwrite of a certificate an investor may already hold.
 *
 * <p>Determinism (DL-BE-069): the issuance timestamp is captured once and stored verbatim on
 * {@code form_16a_issued_at}; {@link #download} re-renders from that <i>same</i> stored value (never
 * {@code now()}), so a later download reproduces byte-identical output and the stored
 * {@code form_16a_doc_hash} verifies against a fresh render.
 */
@Service
public class Form16aService {

    static final String CONTEXT = "tax";
    private static final Set<String> COMPLIANCE = Set.of(AdminRole.COMPLIANCE_REVIEWER.wire());
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbc;
    private final CommandGateway gateway;

    public Form16aService(JdbcTemplate jdbc, CommandGateway gateway) {
        this.jdbc = jdbc;
        this.gateway = gateway;
    }

    /** Issues the Form 16A for (investorId, fyCode). Rejects an already-issued profile. */
    public CommandResult<byte[]> issue(CommandRequest request, UUID investorId, String fyCode) {
        return gateway.execute(request, COMPLIANCE, () -> {
            Profile profile = loadProfile(investorId, fyCode);
            if (profile.issued()) {
                throw new ValidationException(
                        "Form 16A already issued for investor " + investorId + " FY " + fyCode);
            }
            String pan = loadPan(investorId);
            List<Form16aLine> lines = loadLines(investorId, fyCode);
            // Captured once, truncated to Postgres's TIMESTAMPTZ microsecond precision so the value we
            // render matches exactly what round-trips back from the DB on download (DL-BE-067).
            OffsetDateTime issuedAt = OffsetDateTime.now(IST).truncatedTo(ChronoUnit.MICROS);

            Form16aData data = new Form16aData(investorId, pan, fyCode, profile.panVerified(), profile.tdsRateBps(),
                    profile.cumulativeGrossPaise(), profile.cumulativeTdsPaise(), issuedAt, lines);
            byte[] bytes = Form16aRenderer.render(data);
            byte[] hash = Form16aRenderer.sha256(bytes);

            jdbc.update("INSERT INTO sys_document_object (doc_hash, content_type, originating_context, "
                            + "originating_aggregate_ref, byte_size, encryption_key_ref) "
                            + "VALUES (?, 'text/plain; charset=utf-8', 'bc12_tax', ?, ?, 'stub-kms-key') "
                            + "ON CONFLICT (doc_hash) DO NOTHING",
                    hash, "TaxYearProfile:" + investorId + ":" + fyCode, (long) bytes.length);

            // Status-guarded (the WS-6/WS-7 rowcount lesson) — the shape CHECK also backstops.
            int updated = jdbc.update("UPDATE tax_year_profile SET form_16a_issued = TRUE, form_16a_doc_hash = ?, "
                            + "form_16a_issued_at = ?, aggregate_version = aggregate_version + 1 "
                            + "WHERE investor_id = ? AND fy_code = ? AND form_16a_issued = FALSE",
                    hash, issuedAt, investorId, fyCode);
            if (updated != 1) {
                throw new ValidationException(
                        "Form 16A is no longer un-issued for investor " + investorId + " FY " + fyCode);
            }

            // Persist the rendered bytes so the certificate is a FROZEN artifact (V9): download returns these
            // verbatim rather than re-deriving from tables that keep changing on later same-FY distributions.
            jdbc.update("INSERT INTO tax_investor_statement (investor_id, period, kind, doc_hash, doc_content) "
                    + "VALUES (?, ?, 'form_16a'::tax_investor_statement_kind, ?, ?)",
                    investorId, fyCode, hash, bytes);

            CommandEvent event = new CommandEvent(CONTEXT + ".TaxYearProfile.Form16aIssued", profile.version() + 1,
                    Map.of("investor_id", investorId.toString(), "fy_code", fyCode,
                            "doc_hash", HexFormat.of().formatHex(hash),
                            "cumulative_income_paise", profile.cumulativeGrossPaise(),
                            "cumulative_tds_paise", profile.cumulativeTdsPaise()),
                    Map.of(), Map.of("form_16a_issued", true), true);
            return new CommandOutcome<>(hash, event);
        });
    }

    /**
     * Returns the FROZEN certificate bytes stored at issuance (V9). The document is immutable once issued, so
     * we serve the persisted bytes verbatim — never a re-render over {@code tax_year_profile} cumulatives /
     * {@code tax_tds_deduction} lines, which keep changing on later same-FY distributions and would make a
     * re-render diverge from the certificate the investor was issued. The stored hash is re-verified as a
     * tamper check.
     */
    public byte[] download(UUID investorId, String fyCode) {
        StoredDoc doc = jdbc.query("SELECT doc_content, doc_hash FROM tax_investor_statement "
                        + "WHERE investor_id = ? AND period = ? AND kind = 'form_16a'::tax_investor_statement_kind",
                rs -> rs.next() ? new StoredDoc(rs.getBytes("doc_content"), rs.getBytes("doc_hash")) : null,
                investorId, fyCode);
        if (doc == null || doc.content() == null) {
            throw new NotFoundException("Form 16A not issued for investor " + investorId + " FY " + fyCode);
        }
        if (!Arrays.equals(Form16aRenderer.sha256(doc.content()), doc.docHash())) {
            throw new IllegalStateException(
                    "Form 16A stored bytes fail their hash for investor " + investorId + " FY " + fyCode);
        }
        return doc.content();
    }

    // --- loads -------------------------------------------------------------------------------------

    private Profile loadProfile(UUID investorId, String fyCode) {
        Profile p = jdbc.query("SELECT form_16a_issued, pan_verified, tds_rate_bps, cumulative_gross_paise, "
                        + "cumulative_tds_paise, aggregate_version, form_16a_issued_at, form_16a_doc_hash "
                        + "FROM tax_year_profile WHERE investor_id = ? AND fy_code = ?",
                rs -> rs.next() ? new Profile(rs.getBoolean("form_16a_issued"), rs.getBoolean("pan_verified"),
                        rs.getInt("tds_rate_bps"), rs.getLong("cumulative_gross_paise"),
                        rs.getLong("cumulative_tds_paise"), rs.getInt("aggregate_version"),
                        rs.getObject("form_16a_issued_at", OffsetDateTime.class), rs.getBytes("form_16a_doc_hash"))
                        : null,
                investorId, fyCode);
        if (p == null) {
            throw new NotFoundException("tax year profile not found for investor " + investorId + " FY " + fyCode);
        }
        return p;
    }

    private String loadPan(UUID investorId) {
        return jdbc.query("SELECT pan FROM inv_account WHERE investor_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, investorId);
    }

    private List<Form16aLine> loadLines(UUID investorId, String fyCode) {
        return jdbc.query("SELECT listing_id, gross_paise, tds_amount_paise, net_paise, challan_ref "
                        + "FROM tax_tds_deduction WHERE investor_id = ? AND fy_code = ? ORDER BY listing_id",
                (rs, n) -> new Form16aLine(rs.getObject("listing_id", UUID.class), rs.getLong("gross_paise"),
                        rs.getLong("tds_amount_paise"), rs.getLong("net_paise"), rs.getString("challan_ref")),
                investorId, fyCode);
    }

    private record Profile(boolean issued, boolean panVerified, int tdsRateBps, long cumulativeGrossPaise,
                           long cumulativeTdsPaise, int version, OffsetDateTime issuedAt, byte[] docHash) {
    }

    private record StoredDoc(byte[] content, byte[] docHash) {
    }
}
