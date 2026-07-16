package com.arthvritt.platform.compliance;

import com.arthvritt.platform.auth.AuthSession;
import com.arthvritt.platform.shared.error.NotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BE-2 (UI_INTEGRATION_BACKEND_SPEC) — resolves the {@code kyc_file_id} for a supplier/investor subject, so
 * the UI can reach {@code POST /kyc/{kycFileId}/documents} (which otherwise has no way to discover the id).
 * {@code comp_kyc_file} has a UNIQUE {@code (subject_id, subject_type)} — exactly one row per subject, created
 * at {@code submit-kyc} — so this is a native one-row read (mirrors {@code TaxQueryController}); a subject that
 * has not submitted KYC yet resolves to 404, and the UI hides the KYC-doc panel until then.
 */
@RestController
public class KycFileController {

    private final JdbcTemplate jdbc;

    public KycFileController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/suppliers/{id}/kyc-file")
    public Map<String, Object> supplierKycFile(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id) {
        return resolve(id, "supplier");
    }

    @GetMapping("/investors/{id}/kyc-file")
    public Map<String, Object> investorKycFile(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id) {
        return resolve(id, "investor");
    }

    private Map<String, Object> resolve(UUID subjectId, String subjectType) {
        Map<String, Object> row = jdbc.query(
                "SELECT kyc_file_id, status::text AS status FROM comp_kyc_file "
                        + "WHERE subject_id = ? AND subject_type = ?::comp_kyc_subject_type",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kyc_file_id", rs.getObject("kyc_file_id", UUID.class).toString());
                    m.put("subject_id", subjectId.toString());
                    m.put("subject_type", subjectType);
                    m.put("status", rs.getString("status"));
                    return m;
                },
                subjectId, subjectType);
        if (row == null) {
            throw new NotFoundException(subjectType + " has no KYC file (KYC not submitted): " + subjectId);
        }
        return row;
    }
}
