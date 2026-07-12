package com.arthvritt.platform.adminiam;

import com.arthvritt.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migration test for V7 ([[DL-BE-064]]): the genesis SoD policy and its reserved SYSTEM principal are present
 * from schema genesis (Flyway), so {@code RbacService.assignRole} never fails closed on a fresh, non-dev
 * database. Asserts the seeded rows by their fixed ids (deterministic even though the shared test container
 * lets other suites supersede the *active* policy) and that the one-active-policy invariant holds.
 */
class GenesisSodPolicySeedTest extends AbstractIntegrationTest {

    private static final UUID SYSTEM_IDENTITY = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SYSTEM_ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID GENESIS_POLICY = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Autowired private JdbcTemplate jdbc;

    @Test
    void the_reserved_system_principal_is_seeded_and_non_interactive() {
        String identityStatus = jdbc.queryForObject(
                "SELECT status::text FROM auth_identity WHERE identity_id = ?", String.class, SYSTEM_IDENTITY);
        assertThat(identityStatus).isEqualTo("disabled");

        String adminStatus = jdbc.queryForObject(
                "SELECT status::text FROM admin_user WHERE admin_user_id = ? AND identity_id = ?",
                String.class, SYSTEM_ADMIN, SYSTEM_IDENTITY);
        assertThat(adminStatus).isEqualTo("disabled");

        // Non-interactive: no credential is ever seeded, so it can never complete password → OTP login.
        Integer credentials = jdbc.queryForObject(
                "SELECT count(*) FROM auth_credential WHERE identity_id = ?", Integer.class, SYSTEM_IDENTITY);
        assertThat(credentials).isZero();
    }

    @Test
    void the_genesis_policy_row_carries_the_phase1_pairs_authored_by_the_system_principal() {
        UUID publishedBy = jdbc.queryForObject(
                "SELECT published_by FROM admin_sod_policy WHERE sod_policy_id = ?", UUID.class, GENESIS_POLICY);
        assertThat(publishedBy).isEqualTo(SYSTEM_ADMIN);

        // The strict pair (credit_reviewer, treasury_and_settlement) and all three soft pairs are present.
        Integer strictMatch = jdbc.queryForObject(
                "SELECT count(*) FROM admin_sod_policy WHERE sod_policy_id = ? "
                        + "AND strict_pairs @> '[[\"credit_reviewer\",\"treasury_and_settlement\"]]'::jsonb",
                Integer.class, GENESIS_POLICY);
        assertThat(strictMatch).isEqualTo(1);

        Integer softMatch = jdbc.queryForObject(
                "SELECT count(*) FROM admin_sod_policy WHERE sod_policy_id = ? "
                        + "AND soft_pairs @> '[[\"super_admin\",\"compliance_reviewer\"],"
                        + "[\"ops_executive\",\"treasury_and_settlement\"],"
                        + "[\"credit_reviewer\",\"compliance_reviewer\"]]'::jsonb",
                Integer.class, GENESIS_POLICY);
        assertThat(softMatch).isEqualTo(1);
    }

    @Test
    void exactly_one_active_policy_exists_so_assignment_never_fails_closed() {
        Integer active = jdbc.queryForObject(
                "SELECT count(*) FROM admin_sod_policy WHERE superseded_by IS NULL", Integer.class);
        assertThat(active).isEqualTo(1); // migration guarantees >= 1; the partial UNIQUE index guarantees <= 1
    }
}
