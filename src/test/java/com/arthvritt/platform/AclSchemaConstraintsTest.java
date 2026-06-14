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
 * Invariant tests for the V4 (generic ACL / audit) audit-fix constraints: command-id dedup,
 * audit-envelope tamper-evidence + actor guards, and notification provider-ref shape.
 */
@Transactional
class AclSchemaConstraintsTest extends AbstractIntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	/** Inserts a sys_audit_event with the given actor JSON and envelope-hash byte length. */
	private void insertAuditEvent(String actorJson, int hashLen) {
		jdbc.update("INSERT INTO sys_audit_event(event_id,event_type,occurred_at,actor,"
						+ "aggregate_type,aggregate_id,aggregate_version,correlation_id,payload,"
						+ "envelope_hash,chain_shard) "
						+ "VALUES (?,?,?,CAST(? AS jsonb),?,?,?,?,CAST(? AS jsonb),?,?)",
				UUID.randomUUID(), "listing.Listing.GoneLive", OffsetDateTime.now().minusMinutes(1), actorJson,
				"Listing", UUID.randomUUID(), 1, UUID.randomUUID(), "{}", new byte[hashLen], "listing:2026-06-13");
	}

	@Test
	void stateTransitionWithoutSnapshots_isRejected() {
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO sys_audit_event(event_id,event_type,occurred_at,actor,aggregate_type,"
						+ "aggregate_id,aggregate_version,correlation_id,payload,envelope_hash,chain_shard,"
						+ "is_state_transition) "
						+ "VALUES (?,?,?,CAST(? AS jsonb),?,?,?,?,CAST(? AS jsonb),?,?,true)",
				UUID.randomUUID(), "listing.Listing.GoneLive", OffsetDateTime.now().minusMinutes(1),
				GOOD_ACTOR, "Listing", UUID.randomUUID(), 1, UUID.randomUUID(), "{}",
				new byte[32], "listing:2026-06-13"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("sys_audit_event_transition_snapshot_chk");
	}

	private static final String GOOD_ACTOR =
			"{\"actor_type\":\"investor\",\"actor_id\":\"a\",\"session_id\":\"s\"}";

	@Test
	void wellFormedAuditEvent_isAccepted() {
		assertThatCode(() -> insertAuditEvent(GOOD_ACTOR, 32)).doesNotThrowAnyException();
	}

	@Test
	void auditEventActorMissingSessionId_isRejected() {
		assertThatThrownBy(() -> insertAuditEvent(
				"{\"actor_type\":\"investor\",\"actor_id\":\"a\"}", 32))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("sys_audit_event_actor_keys");
	}

	@Test
	void adminActorWithoutMfa_isAccepted() {
		// [REVIEW-FIX #2] the blanket admin-MFA CHECK was removed: an admin audit event
		// (e.g. login-success / sensitive read) may legitimately carry no mfa_assertion_id.
		assertThatCode(() -> insertAuditEvent(
				"{\"actor_type\":\"admin_user\",\"actor_id\":\"a\",\"session_id\":\"s\"}", 32))
				.doesNotThrowAnyException();
	}

	@Test
	void auditEventWrongHashLength_isRejected() {
		assertThatThrownBy(() -> insertAuditEvent(GOOD_ACTOR, 16))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("sys_audit_event_envelope_hash_len");
	}

	@Test
	void duplicateCommandId_isRejected() {
		UUID actor = UUID.randomUUID();
		UUID command = UUID.randomUUID();
		jdbc.update("INSERT INTO sys_command_log(actor_id,command_id,command_type,aggregate_type,aggregate_id) "
						+ "VALUES (?,?,?,?,?)",
				actor, command, "GoLive", "Listing", UUID.randomUUID());
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO sys_command_log(actor_id,command_id,command_type,aggregate_type,aggregate_id) "
						+ "VALUES (?,?,?,?,?)",
				actor, command, "GoLive", "Listing", UUID.randomUUID()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("sys_command_log_pk");
	}

	@Test
	void notificationSentWithoutProviderRef_isRejected() {
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO sys_notification_dispatch(dispatch_id,recipient_identity_id,channel,"
						+ "template_id,status) "
						+ "VALUES (?,?,CAST(? AS notification_channel_enum),?,CAST(? AS notification_status_enum))",
				UUID.randomUUID(), UUID.randomUUID(), "email", "tmpl_otp", "sent"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("sys_notification_dispatch_provider_ref_shape_chk");
	}

	@Test
	void verificationFailedWithoutFailureClass_isRejected() {
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO gate_verification(verification_id,subject_id,api_name,status) "
						+ "VALUES (?,?,CAST(? AS verification_api_enum),CAST(? AS verification_status_enum))",
				UUID.randomUUID(), UUID.randomUUID(), "verify_pan", "failed"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("gate_verification_failure_class_shape_chk");
	}
}
