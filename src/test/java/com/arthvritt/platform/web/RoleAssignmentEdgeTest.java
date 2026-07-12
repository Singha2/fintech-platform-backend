package com.arthvritt.platform.web;

import com.arthvritt.platform.auth.AuthService;
import com.arthvritt.platform.shared.Ids;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP edge tests for the BC10 role assign/revoke surface (the controller adapter over
 * {@code RbacService}). Proves the super_admin-gated grant/revoke of any role — including
 * {@code super_admin} — works over the real command edge, and that a non-super_admin is refused
 * {@code role_not_held} with no assignment written. No policy is seeded here on purpose: the genesis
 * SoD policy (V7, [[DL-BE-064]]) is present from migration, so {@code assignRole} never fails closed —
 * this test exercises that end to end.
 */
class RoleAssignmentEdgeTest extends AbstractEdgeHttpTest {

    @Autowired private AuthService authService;

    private Seeded superAdmin;

    @BeforeEach
    void seedSuperAdmin() {
        notifier.clear();
        superAdmin = seedAdminWithRoles("super_admin");
    }

    @Test
    void super_admin_assigns_a_role_over_http_and_the_assignment_goes_active() throws Exception {
        String bearer = bearerFor(superAdmin);
        UUID target = seedTargetAdmin();

        mvc.perform(assign(bearer, target, "ops_executive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emitted_events[0].event_type").value("admin_iam.Role.Assigned"));

        assertThat(activeRoles(target)).containsExactly("ops_executive");
    }

    @Test
    void super_admin_can_grant_super_admin_to_another_admin() throws Exception {
        String bearer = bearerFor(superAdmin);
        UUID target = seedTargetAdmin();

        mvc.perform(assign(bearer, target, "super_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emitted_events[0].event_type").value("admin_iam.Role.Assigned"));

        assertThat(activeRoles(target)).containsExactly("super_admin");
    }

    @Test
    void a_non_super_admin_assigning_a_role_is_403_role_not_held_and_writes_nothing() throws Exception {
        Seeded ops = seedAdminWithRoles("ops_executive");
        String bearer = bearerFor(ops);
        UUID target = seedTargetAdmin();

        mvc.perform(assign(bearer, target, "ops_executive"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));

        assertThat(activeRoles(target)).isEmpty();
    }

    @Test
    void an_unknown_role_is_400_validation_failed() throws Exception {
        String bearer = bearerFor(superAdmin);
        UUID target = seedTargetAdmin();

        mvc.perform(assign(bearer, target, "root"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("validation_failed"));

        assertThat(activeRoles(target)).isEmpty();
    }

    @Test
    void revoke_removes_an_active_assignment() throws Exception {
        String bearer = bearerFor(superAdmin);
        UUID target = seedTargetAdmin();
        mvc.perform(assign(bearer, target, "ops_executive")).andExpect(status().isOk());

        mvc.perform(post("/admin-users/{id}/roles/{role}/revoke", target, "ops_executive")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Command-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emitted_events[0].event_type").value("admin_iam.Role.Revoked"));

        assertThat(activeRoles(target)).isEmpty();
    }

    // --- helpers -----------------------------------------------------------------------------------

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder assign(
            String bearer, UUID target, String role) throws Exception {
        return post("/admin-users/{id}/roles", target)
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("role", role)));
    }

    /** An active admin_user with no roles — a target for assign/revoke. */
    private UUID seedTargetAdmin() {
        String email = "tgt-" + UUID.randomUUID() + "@arthvritt.test";
        UUID identityId = authService.provisionIdentity("admin_user", email, phone(), "Target");
        UUID adminUserId = Ids.newId();
        jdbc.update("INSERT INTO admin_user (admin_user_id, identity_id, email, display_name, status) "
                + "VALUES (?, ?, ?, ?, 'active')", adminUserId, identityId, email, "Target");
        return adminUserId;
    }

    private List<String> activeRoles(UUID adminUserId) {
        return jdbc.queryForList(
                "SELECT role::text FROM admin_role_assignment WHERE admin_user_id = ? AND status = 'active'",
                String.class, adminUserId);
    }
}
