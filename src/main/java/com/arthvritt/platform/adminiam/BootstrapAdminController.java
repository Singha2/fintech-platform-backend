package com.arthvritt.platform.adminiam;

import com.arthvritt.platform.infrastructure.web.RequestBodies;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Break-glass <b>super_admin</b> bootstrap over HTTP, protected by a <b>static API key</b> instead of a
 * session bearer — the one way to mint the first admin before any super_admin (and therefore any login)
 * exists. Restricted to super_admin only (the trust-chain seed); every other admin/role is created via the
 * normal maker-checker {@code /admin-users} flow. The key lives in config ({@code platform.bootstrap.api-key};
 * a local default, a secret-manager value in prod) and is presented as {@code Authorization: Bearer <api-key>}.
 *
 * <p>Security-permitted at the filter chain ({@code /bootstrap/**}) precisely because it authenticates itself
 * here rather than via the session filter. Everything else it does — provisioning an active admin with a role
 * and password — is a genuine break-glass; see {@link BootstrapAdminService}.
 */
@RestController
@RequestMapping("/bootstrap")
public class BootstrapAdminController {

    private final BootstrapAdminService bootstrap;
    private final BootstrapApiKeyGuard apiKey;

    public BootstrapAdminController(BootstrapAdminService bootstrap, BootstrapApiKeyGuard apiKey) {
        this.bootstrap = bootstrap;
        this.apiKey = apiKey;
    }

    @PostMapping("/admin-users")
    public ResponseEntity<Map<String, Object>> provisionAdmin(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> body) {
        apiKey.requireValidKey(authorization);

        String email = RequestBodies.requiredString(body, "email");
        String displayName = RequestBodies.requiredString(body, "display_name");
        String phone = RequestBodies.requiredString(body, "phone");
        String password = RequestBodies.requiredString(body, "password");

        UUID adminUserId = bootstrap.provisionSuperAdmin(email, displayName, phone, password);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "admin_user_id", adminUserId.toString(),
                "email", email,
                "role", AdminRole.SUPER_ADMIN.wire(),
                "status", "active"));
    }
}
