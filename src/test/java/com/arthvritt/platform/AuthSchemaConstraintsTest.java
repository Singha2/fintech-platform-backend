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
 * Invariant tests for the V3 (auth) schema: the audit-added assertion_id uniqueness and a
 * representative existing shape CHECK. {@code @Transactional} rolls each test back.
 */
@Transactional
class AuthSchemaConstraintsTest extends AbstractIntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	private UUID seedIdentity() {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO auth_identity(identity_id,kind,email) "
						+ "VALUES (?,CAST(? AS identity_kind_enum),?)",
				id, "admin_user", id + "@example.com");
		return id;
	}

	private void insertConsumedChallenge(UUID identity, UUID assertionId) {
		jdbc.update("INSERT INTO auth_otp_challenge(challenge_id,identity_id,purpose,code_hash,"
						+ "delivery_channel,expires_at,consumed_at,status,assertion_id) "
						+ "VALUES (?,?,CAST(? AS otp_purpose_enum),?,?,?,?,CAST(? AS otp_status_enum),?)",
				UUID.randomUUID(), identity, "login_mfa", "codehash", "email",
				OffsetDateTime.now().plusMinutes(5), OffsetDateTime.now(), "consumed", assertionId);
	}

	@Test
	void duplicateAssertionId_isRejected() {
		UUID identity = seedIdentity();
		UUID assertion = UUID.randomUUID();
		insertConsumedChallenge(identity, assertion);
		assertThatThrownBy(() -> insertConsumedChallenge(identity, assertion))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uidx_auth_otp_challenge_assertion");
	}

	@Test
	void distinctAssertionIds_areAccepted() {
		UUID identity = seedIdentity();
		insertConsumedChallenge(identity, UUID.randomUUID());
		assertThatCode(() -> insertConsumedChallenge(identity, UUID.randomUUID()))
				.doesNotThrowAnyException();
	}

	@Test
	void passwordCredentialWithoutSecret_isRejected() {
		UUID identity = seedIdentity();
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO auth_credential(credential_id,identity_id,kind,secret_hash) "
						+ "VALUES (?,?,CAST(? AS credential_kind_enum),NULL)",
				UUID.randomUUID(), identity, "password"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("auth_credential_password_shape");
	}

	@Test
	void assertionIdWithoutConsumption_isRejected() {
		// assertion_id may only be set on a consumed challenge (existing shape CHECK)
		UUID identity = seedIdentity();
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO auth_otp_challenge(challenge_id,identity_id,purpose,code_hash,"
						+ "delivery_channel,expires_at,status,assertion_id) "
						+ "VALUES (?,?,CAST(? AS otp_purpose_enum),?,?,?,CAST(? AS otp_status_enum),?)",
				UUID.randomUUID(), identity, "login_mfa", "codehash", "email",
				OffsetDateTime.now().plusMinutes(5), "active", UUID.randomUUID()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("auth_otp_challenge_assertion_only_when_consumed");
	}
}
