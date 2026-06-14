package com.arthvritt.platform;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Flyway migrations applied cleanly to a real Postgres and produced the expected
 * schema. Version-agnostic: asserts every migration succeeded and that representative objects
 * from each migration exist, rather than pinning exact table counts (which grow per migration).
 */
class SchemaMigrationTest extends AbstractIntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void allMigrationsSucceeded() {
		Integer failed = jdbc.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE success = false", Integer.class);
		assertThat(failed).as("no failed Flyway migrations").isZero();

		List<String> versions = jdbc.queryForList(
				"SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank",
				String.class);
		assertThat(versions).as("V1–V4 applied").contains("1", "2", "3", "4");
	}

	@Test
	void sharedDomainsExist() {
		List<String> domains = jdbc.queryForList(
				"SELECT domain_name FROM information_schema.domains WHERE domain_schema = 'public'",
				String.class);
		// contains (not exact set) so a future domain (e.g. cin_type) doesn't break this test,
		// matching the version-agnostic intent of the sibling assertions.
		assertThat(domains).contains(
				"pan_type", "gstin_type", "ifsc_type", "irn_type", "aadhaar_last4_type",
				"money_paise", "positive_money_paise", "bps_type");
	}

	@Test
	void representativeTablesExist() {
		List<String> tables = jdbc.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
						+ "AND table_type = 'BASE TABLE'",
				String.class);
		assertThat(tables).as("V1 core").contains(
				"deal_invoice", "deal_listing", "sub_subscription", "cash_payout_instruction");
		assertThat(tables).as("V2 counterparty/admin/compliance").contains(
				"inv_account", "sup_account", "buyer_account", "admin_user",
				"comp_kyc_file", "tax_year_profile", "audit_account");
		assertThat(tables).as("V3 auth").contains(
				"auth_identity", "auth_credential", "auth_mfa_factor",
				"auth_otp_challenge", "auth_session");
		assertThat(tables).as("V4 generic-acl + command log").contains(
				"sys_audit_event", "sys_command_log", "sys_notification_dispatch",
				"sys_document_object", "gate_verification", "gate_vendor_instruction",
				"gate_inflow_observation", "gate_signature_session");
	}

	@Test
	void moneyAndRateColumnsUseDomains() {
		// V1
		assertThat(domainOf("deal_invoice", "face_value")).isEqualTo("positive_money_paise");
		assertThat(domainOf("risk_pricing_policy", "fee_bps")).isEqualTo("bps_type");
		// V2
		assertThat(domainOf("tax_year_profile", "tds_rate_bps")).isEqualTo("bps_type");
		assertThat(domainOf("sup_account", "credit_exposure_cap_paise")).isEqualTo("money_paise");
		assertThat(domainOf("tax_tds_deduction", "net_paise")).isEqualTo("positive_money_paise");
	}

	private String domainOf(String table, String column) {
		return jdbc.queryForObject(
				"SELECT domain_name FROM information_schema.columns "
						+ "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
				String.class, table, column);
	}
}
