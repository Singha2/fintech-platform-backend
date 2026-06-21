package com.arthvritt.platform.auth;

import java.util.UUID;

/**
 * Outcome of {@link AuthService#authenticatePassword}. {@code authenticated} true means the password
 * matched and MFA is the required next step; {@code identityId} is the authenticated identity (null
 * when not authenticated — failures are deliberately indistinguishable to the caller).
 */
public record PasswordResult(boolean authenticated, UUID identityId) {
}
