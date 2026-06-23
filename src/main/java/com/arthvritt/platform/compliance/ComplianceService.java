package com.arthvritt.platform.compliance;

import com.arthvritt.platform.command.CommandRejectedException;
import com.arthvritt.platform.shared.Ids;
import com.arthvritt.platform.shared.error.ValidationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * BC11 Compliance — the WS-1 <b>auto-approve stub</b> over {@code comp_kyc_file}, and the one-place
 * swap-point for the real KYC/AML engine at M15. It is invoked by the onboarding flows (BC8 supplier,
 * later BC7 investor) <i>inside the calling command's transaction</i> (no own {@code @Transactional}), so
 * the KYC file row and the caller's state transition commit atomically. Phase-1 has no AML/PEP screening
 * and no maker-checker on the approval — the same {@code approveKyc} method the real engine will own.
 *
 * <p>WS-1 records the KYC file state only; a distinct {@code compliance.KycFile.Approved} audit envelope is
 * deferred — the caller's command envelope (e.g. {@code supplier.Supplier.KycApproved}) is the audit record
 * for the slice (DL-BE-031).
 */
@Service
public class ComplianceService {

    private final JdbcTemplate jdbc;

    public ComplianceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records a submitted KYC file for the subject (one per {@code (subject_id, subject_type)}).
     * {@code submittedBy} is the maker — KYC approval is DB-enforced maker-checker (KF.2/C4): the approver
     * must differ from this submitter.
     */
    public void submitKyc(UUID subjectId, String subjectType, UUID submittedByAdminUserId) {
        jdbc.update("INSERT INTO comp_kyc_file (kyc_file_id, subject_id, subject_type, status, submitted_by) "
                        + "VALUES (?, ?, ?::comp_kyc_subject_type, 'submitted', ?)",
                Ids.newId(), subjectId, subjectType, submittedByAdminUserId);
    }

    /**
     * Auto-approves the subject's submitted KYC file. The DB enforces the controls: {@code approver_id}
     * (FK → admin_user) + {@code decided_at} once approved, {@code approver_id <> submitted_by} (maker ≠
     * checker, KF.2/C4), and {@code approver_mfa_assertion_id} present (the checker's fresh MFA).
     */
    public void approveKyc(UUID subjectId, String subjectType, UUID approverAdminUserId,
                           String approverMfaAssertionId) {
        UUID submittedBy = jdbc.query("SELECT submitted_by FROM comp_kyc_file "
                        + "WHERE subject_id = ? AND subject_type = ?::comp_kyc_subject_type AND status = 'submitted'",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, subjectId, subjectType);
        if (submittedBy == null) {
            throw new ValidationException("no submitted KYC file to approve for subject: " + subjectId);
        }
        // Maker ≠ checker (KF.2/C4) enforced in-app for a clean 409, with the DB CHECK as the backstop.
        if (approverAdminUserId.equals(submittedBy)) {
            throw CommandRejectedException.checkerEqualsMaker();
        }
        jdbc.update("UPDATE comp_kyc_file SET status = 'approved', approver_id = ?, "
                        + "approver_mfa_assertion_id = ?, decided_at = now(), "
                        + "aggregate_version = aggregate_version + 1 "
                        + "WHERE subject_id = ? AND subject_type = ?::comp_kyc_subject_type AND status = 'submitted'",
                approverAdminUserId, approverMfaAssertionId, subjectId, subjectType);
    }
}
