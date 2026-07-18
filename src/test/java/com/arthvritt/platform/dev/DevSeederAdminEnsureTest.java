package com.arthvritt.platform.dev;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DL-BE-087 (DF-3) — {@link DevDataSeeder} admin seeding is <b>ensure-missing per email</b>, so an account
 * added to the seed list after a DB was first seeded (e.g. {@code ops2@dev.local}, DL-BE-086) materialises on
 * a pre-existing dev DB without a wipe. This is the case the old all-or-nothing empty-guard missed: on a
 * <b>non-empty</b> admin table the seeder used to skip entirely. Runs under {@code dev} (its own container;
 * {@code DevDataSeeder} seeds all seven admins + counterparties at boot).
 */
@ActiveProfiles("dev")
class DevSeederAdminEnsureTest extends AbstractEdgeHttpTest {

    @Autowired
    private DevDataSeeder seeder;

    @Test
    void ensures_a_missing_admin_on_a_non_empty_db_then_login_works_without_reseeding() throws Exception {
        // Boot already seeded all seven admins + the counterparties. Simulate a pre-ops2 DB (six admins, a
        // populated schema) by removing ops2 and everything hanging off it.
        long suppliersBefore = count("SELECT count(*) FROM sup_account");
        long adminsBefore = count("SELECT count(*) FROM admin_user WHERE email = 'ops2@dev.local'::citext");
        assertThat(adminsBefore).isEqualTo(1);
        removeAdmin("ops2@dev.local");
        assertThat(count("SELECT count(*) FROM admin_user WHERE email = 'ops2@dev.local'::citext")).isZero();

        // Re-run the seeder against the now non-empty (six-admin) DB — the old empty-guard would have skipped.
        seeder.run(null);

        // DoD 1: ops2 is (re)created and can log in; the other admins are untouched.
        assertThat(count("SELECT count(*) FROM admin_user WHERE email = 'ops2@dev.local'::citext")).isEqualTo(1);
        mvc.perform(post("/auth/login/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("email", "ops2@dev.local", "password", DevDataSeeder.PASSWORD))))
                .andExpect(status().isOk());

        // DoD: counterparties are NOT re-seeded (the guard is counterparty-emptiness, not admin count).
        assertThat(count("SELECT count(*) FROM sup_account")).isEqualTo(suppliersBefore);

        // DoD 3: rebooting twice is a no-op — no duplicate admin/identity/credential/role rows.
        seeder.run(null);
        assertThat(count("SELECT count(*) FROM admin_user WHERE email = 'ops2@dev.local'::citext")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM auth_identity WHERE email = 'ops2@dev.local'::citext")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM auth_credential c JOIN auth_identity i ON i.identity_id = c.identity_id "
                + "WHERE i.email = 'ops2@dev.local'::citext AND c.kind = 'password'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM admin_role_assignment r JOIN admin_user a "
                + "ON a.admin_user_id = r.admin_user_id WHERE a.email = 'ops2@dev.local'::citext")).isEqualTo(1);
    }

    /** Tears an admin down (respecting the RESTRICT FKs) to simulate a DB seeded before the account existed. */
    private void removeAdmin(String email) {
        UUID adminUserId = jdbc.queryForObject("SELECT admin_user_id FROM admin_user WHERE email = ?::citext",
                UUID.class, email);
        UUID identityId = jdbc.queryForObject("SELECT identity_id FROM admin_user WHERE admin_user_id = ?",
                UUID.class, adminUserId);
        jdbc.update("DELETE FROM admin_role_assignment WHERE admin_user_id = ?", adminUserId);
        jdbc.update("DELETE FROM admin_user WHERE admin_user_id = ?", adminUserId);
        jdbc.update("DELETE FROM auth_credential WHERE identity_id = ?", identityId);
        jdbc.update("DELETE FROM auth_identity WHERE identity_id = ?", identityId);
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0L : n;
    }
}
