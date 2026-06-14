package com.arthvritt.platform;

import java.time.LocalDate;
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
 * Invariant tests proving the V1 (core) schema enforces its load-bearing guarantees at the DB
 * layer — the "last line of defence" per CLAUDE.md. Each test asserts a malformed row is rejected
 * (and, where useful, that the well-formed counterpart is accepted), and checks the violated
 * constraint by name so a future schema change can't silently weaken the guard.
 *
 * <p>{@code @Transactional} rolls each test back, so the shared container stays clean.
 */
@Transactional
class CoreSchemaConstraintsTest extends AbstractIntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	// --- seed helpers (FK parents) ------------------------------------------------------------

	private UUID seedInvoice() {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO deal_invoice(invoice_id,supplier_id,buyer_id,invoice_number,"
				+ "face_value,invoice_date,tenor_days,due_date) VALUES (?,?,?,?,?,?,?,?)",
				id, UUID.randomUUID(), UUID.randomUUID(), "INV", 100_000L,
				LocalDate.of(2026, 1, 1), 30, LocalDate.of(2026, 1, 31));
		return id;
	}

	private UUID seedListing(String status, String terminalOutcome) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO deal_listing(listing_id,invoice_id,supplier_id,buyer_id,status,"
				+ "terminal_outcome) VALUES (?,?,?,?,CAST(? AS deal_listing_status),"
				+ "CAST(? AS deal_terminal_outcome))",
				id, seedInvoice(), UUID.randomUUID(), UUID.randomUUID(), status, terminalOutcome);
		return id;
	}

	// --- FN1: cash_payout_instruction kind discriminates the owning aggregate -----------------

	@Test
	void payout_refundCarryingListingId_isRejected() {
		// listing_id references a real listing so the ONLY violation is the kind/target CHECK
		UUID listing = seedListing("live", null);
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO cash_payout_instruction(payout_instruction_id,kind,listing_id,"
						+ "subscription_id,gross_amount,net_amount,fee_amount,maker_id) "
						+ "VALUES (?,CAST(? AS cash_payout_kind),?,?,?,?,?,?)",
				UUID.randomUUID(), "refund", listing, null, 100L, 100L, 0L, UUID.randomUUID()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("cash_payout_instruction_kind_target_chk");
	}

	// --- #1: a closed listing must record its terminal_outcome, and only a closed one may ------

	@Test
	void listing_closedWithoutTerminalOutcome_isRejected() {
		assertThatThrownBy(() -> seedListing("closed", null))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("deal_listing_terminal_outcome_shape_chk");
	}

	@Test
	void listing_liveCarryingTerminalOutcome_isRejected() {
		assertThatThrownBy(() -> seedListing("live", "distributed"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("deal_listing_terminal_outcome_shape_chk");
	}

	@Test
	void listing_closedWithTerminalOutcome_isAccepted() {
		assertThatCode(() -> seedListing("closed", "distributed")).doesNotThrowAnyException();
	}

	// --- #3: a default classification with a checker must record the MFA assertion ------------

	@Test
	void defaultCase_classifiedWithCheckerButNoMfa_isRejected() {
		UUID listing = seedListing("live", null);
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO risk_default_case(case_id,listing_id,status,outcome,classified_at,"
						+ "maker_id,checker_id) VALUES (?,?,CAST(? AS risk_default_case_status),"
						+ "CAST(? AS risk_default_outcome),?,?,?)",
				UUID.randomUUID(), listing, "classified", "defaulted",
				OffsetDateTime.now(), UUID.randomUUID(), UUID.randomUUID()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("risk_default_case_checker_mfa_chk");
	}

	@Test
	void defaultCase_classifiedWithCheckerAndMfa_isAccepted() {
		UUID listing = seedListing("live", null);
		assertThatCode(() -> jdbc.update(
				"INSERT INTO risk_default_case(case_id,listing_id,status,outcome,classified_at,"
						+ "maker_id,checker_id,checker_mfa_assertion_id) "
						+ "VALUES (?,?,CAST(? AS risk_default_case_status),"
						+ "CAST(? AS risk_default_outcome),?,?,?,?)",
				UUID.randomUUID(), listing, "classified", "defaulted",
				OffsetDateTime.now(), UUID.randomUUID(), UUID.randomUUID(), "mfa-assertion-1"))
				.doesNotThrowAnyException();
	}

	// --- money / rate domains -----------------------------------------------------------------

	@Test
	void domain_negativeFaceValue_isRejected() {
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO deal_invoice(invoice_id,supplier_id,buyer_id,invoice_number,"
						+ "face_value,invoice_date,tenor_days,due_date) VALUES (?,?,?,?,?,?,?,?)",
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "INV", -1L,
				LocalDate.of(2026, 1, 1), 30, LocalDate.of(2026, 1, 31)))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("positive_money_paise");
	}

	@Test
	void domain_bpsOverCeiling_isRejected() {
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO risk_pricing_policy(pricing_band_id,buyer_id,tenor_bucket,"
						+ "rate_range_min_bps,rate_range_max_bps,fee_bps,effective_from) "
						+ "VALUES (?,?,CAST(? AS risk_tenor_bucket),?,?,?,?)",
				UUID.randomUUID(), UUID.randomUUID(), "lte_30d", 100, 2000, 200_000,
				LocalDate.of(2026, 1, 1)))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("bps_type");
	}

	// --- four-eyes mandatory above Rs 1 Cr ----------------------------------------------------

	@Test
	void fourEyes_creditLimitOverThresholdWithoutApprover_isRejected() {
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO risk_buyer_profile(buyer_id,sector,rating_source,rating,"
						+ "credit_limit,tenor_cap_days) VALUES (?,?,?,?,?,?)",
				UUID.randomUUID(), "sector", "src", "A", 20_000_000_000L, 90))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("risk_buyer_profile_four_eyes_required_chk");
	}

	@Test
	void fourEyes_creditLimitOverThresholdWithApprover_isAccepted() {
		assertThatCode(() -> jdbc.update(
				"INSERT INTO risk_buyer_profile(buyer_id,sector,rating_source,rating,"
						+ "credit_limit,tenor_cap_days,four_eyes_approval_ref,second_approver_id) "
						+ "VALUES (?,?,?,?,?,?,?,?)",
				UUID.randomUUID(), "sector", "src", "A", 20_000_000_000L, 90,
				"approval-ref-1", UUID.randomUUID()))
				.doesNotThrowAnyException();
	}
}
