package com.arthvritt.platform;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Invariant tests for the V2 (counterparty/admin/compliance/tax) audit-fix constraints.
 * {@code @Transactional} rolls each test back so the shared container stays clean.
 */
@Transactional
class CounterpartySchemaConstraintsTest extends AbstractIntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	/** Seeds an auth_identity (V3 adds admin_user.identity_id FK) + admin_user, returns admin id. */
	private UUID seedAdmin() {
		UUID identity = UUID.randomUUID();
		jdbc.update("INSERT INTO auth_identity(identity_id,kind,email) "
						+ "VALUES (?,CAST(? AS identity_kind_enum),?)",
				identity, "admin_user", identity + "@example.com");
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO admin_user(admin_user_id,identity_id,email,display_name) VALUES (?,?,?,?)",
				id, identity, id + "@example.com", "Admin");
		return id;
	}

	@Test
	void tdsRateOverBpsCeiling_isRejected() {
		// composite PK (investor_id, fy_code); tds_rate_bps now bps_type (0..100000)
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO tax_year_profile(investor_id,fy_code,tds_rate_bps) VALUES (?,?,?)",
				UUID.randomUUID(), "FY2026-27", 200_000))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("bps_type");
	}

	@Test
	void form16aIssuedWithoutCompanionColumns_isRejected() {
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO tax_year_profile(investor_id,fy_code,form_16a_issued) VALUES (?,?,true)",
				UUID.randomUUID(), "FY2026-27"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("tax_year_profile_form16a_shape_chk");
	}

	@Test
	void amlAdjudicationDecisionOutsideKnownSet_isRejected() {
		// adjudicated_by references a real admin so the ONLY violation is the decision-values CHECK
		UUID adjudicator = seedAdmin();
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO comp_aml_screening(screening_id,subject_id,subject_type,status,"
						+ "adjudication_decision,adjudicated_by,adjudicated_at) "
						+ "VALUES (?,?,CAST(? AS comp_aml_subject_type),CAST(? AS comp_aml_screening_status),?,?,?)",
				UUID.randomUUID(), UUID.randomUUID(), "investor", "adjudicated",
				"maybe_suspicious", adjudicator, OffsetDateTime.now()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("comp_aml_screening_decision_values_chk");
	}

	@Test
	void supplierSuspendedWithoutReason_isRejected() {
		// maker/checker/MFA supplied so the ONLY violation is the missing reason
		UUID maker = seedAdmin();
		UUID checker = seedAdmin();
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO sup_account(supplier_id,legal_name,constitution_type,pan,status,suspended_at,"
						+ "suspend_maker_id,suspend_checker_id,suspend_checker_mfa_assertion_id) "
						+ "VALUES (?,?,CAST(? AS sup_constitution_type),?,CAST(? AS sup_account_status),?,?,?,?)",
				UUID.randomUUID(), "Acme Pvt Ltd", "private_limited", "AAAAA1111A", "suspended",
				OffsetDateTime.now(), maker, checker, "mfa-1"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("sup_account_suspend_shape_chk");
	}

	@Test
	void supplierSuspendedWithoutMakerChecker_isRejected() {
		// reason present but no two-person record → the spec-mandated maker-checker CHECK fires
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO sup_account(supplier_id,legal_name,constitution_type,pan,status,"
						+ "suspended_at,suspension_reason) "
						+ "VALUES (?,?,CAST(? AS sup_constitution_type),?,CAST(? AS sup_account_status),?,?)",
				UUID.randomUUID(), "Acme Pvt Ltd", "private_limited", "AAAAA1111A", "suspended",
				OffsetDateTime.now(), "AML true hit"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("sup_account_suspend_makerchecker_required");
	}

	@Test
	void supplierSuspendedWithReasonAndMakerChecker_isAccepted() {
		UUID maker = seedAdmin();
		UUID checker = seedAdmin();
		assertThatCode(() -> jdbc.update(
				"INSERT INTO sup_account(supplier_id,legal_name,constitution_type,pan,status,"
						+ "suspended_at,suspension_reason,"
						+ "suspend_maker_id,suspend_checker_id,suspend_checker_mfa_assertion_id) "
						+ "VALUES (?,?,CAST(? AS sup_constitution_type),?,CAST(? AS sup_account_status),?,?,?,?,?)",
				UUID.randomUUID(), "Acme Pvt Ltd", "private_limited", "AAAAA1111A", "suspended",
				OffsetDateTime.now(), "AML true hit", maker, checker, "mfa-1"))
				.doesNotThrowAnyException();
	}

	@Test
	void kycFileApproverEqualsSubmitter_isRejected() {
		UUID person = seedAdmin();
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO comp_kyc_file(kyc_file_id,subject_id,subject_type,status,approver_id,"
						+ "decided_at,approver_mfa_assertion_id,submitted_by) "
						+ "VALUES (?,?,CAST(? AS comp_kyc_subject_type),CAST(? AS comp_kyc_file_status),?,?,?,?)",
				UUID.randomUUID(), UUID.randomUUID(), "supplier", "approved",
				person, OffsetDateTime.now(), "mfa-1", person))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("comp_kyc_file_maker_ne_checker");
	}
}
