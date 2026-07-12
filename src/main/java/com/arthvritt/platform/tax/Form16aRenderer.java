package com.arthvritt.platform.tax;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Renders the Form 16A TDS certificate as a deterministic, canonical plain-text document (M16, DL-BE-069).
 * Pure and clock-free: {@code issuedAt} is a caller-supplied parameter, never read from a clock, so
 * re-rendering the same {@link Form16aData} — as the download endpoint does on every fetch — reproduces
 * byte-identical output, and therefore the same SHA-256 hash stored on {@code tax_year_profile} at issuance.
 */
final class Form16aRenderer {

    private static final DateTimeFormatter ISSUED_AT_FORMAT = DateTimeFormatter.ISO_INSTANT;

    private Form16aRenderer() {
    }

    /** Renders the certificate bytes (UTF-8 plain text) from {@code data}. Deterministic — no wall clock. */
    static byte[] render(Form16aData data) {
        StringBuilder sb = new StringBuilder();
        sb.append("FORM 16A — CERTIFICATE OF TAX DEDUCTED AT SOURCE\n");
        sb.append("==================================================================\n");
        sb.append("Investor ID     : ").append(data.investorId()).append('\n');
        sb.append("PAN             : ").append(data.pan() == null ? "NOT ON FILE" : data.pan()).append('\n');
        sb.append("PAN Verified    : ").append(data.panVerified()).append('\n');
        sb.append("Financial Year  : ").append(data.fyCode()).append('\n');
        sb.append("TDS Rate (bps)  : ").append(data.tdsRateBps()).append('\n');
        // Canonicalise on the instant (not the offset the JDBC driver happens to hand back on a
        // read-back OffsetDateTime) so a fresh render from the stored value byte-matches the original.
        sb.append("Issued At       : ").append(ISSUED_AT_FORMAT.format(data.issuedAt().toInstant())).append('\n');
        sb.append("------------------------------------------------------------------\n");
        sb.append("Per-listing deductions (paise):\n");
        for (Form16aLine line : data.lines()) {
            sb.append("  listing=").append(line.listingId())
                    .append(" gross=").append(line.grossPaise())
                    .append(" tds=").append(line.tdsAmountPaise())
                    .append(" net=").append(line.netPaise())
                    .append(" challan=").append(line.challanRef() == null ? "-" : line.challanRef())
                    .append('\n');
        }
        sb.append("------------------------------------------------------------------\n");
        sb.append("Cumulative taxable income (paise) : ").append(data.cumulativeIncomePaise()).append('\n');
        sb.append("Cumulative TDS deposited (paise)  : ").append(data.cumulativeTdsPaise()).append('\n');
        sb.append("==================================================================\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** SHA-256 of {@code bytes}, for content addressing into {@code sys_document_object} (DO.1). */
    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** The rendering inputs for one investor × FY Form 16A certificate. */
    record Form16aData(UUID investorId, String pan, String fyCode, boolean panVerified, int tdsRateBps,
                       long cumulativeIncomePaise, long cumulativeTdsPaise, OffsetDateTime issuedAt,
                       List<Form16aLine> lines) {
    }

    /** One {@code tax_tds_deduction} line item on the certificate. */
    record Form16aLine(UUID listingId, long grossPaise, long tdsAmountPaise, long netPaise, String challanRef) {
    }
}
