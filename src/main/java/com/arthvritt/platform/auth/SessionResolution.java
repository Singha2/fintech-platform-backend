package com.arthvritt.platform.auth;

/**
 * Outcome of {@link SessionService#resolveSession}. A terminal session (not found, idle/absolute
 * expired, or revoked) is <b>normal flow</b>, not an exception — the caller gets a {@code reason} it
 * can surface to the frontend (e.g. {@code session_expired}) and decide whether to force re-login.
 *
 * @param active  true only when the session is live; its idle window was rolled forward
 * @param session the live session (null unless {@code active})
 * @param reason  machine-readable terminal reason when not active (not_found, idle_expired,
 *                absolute_expired, revoked)
 */
public record SessionResolution(boolean active, AuthSession session, String reason) {

    public static SessionResolution active(AuthSession session) {
        return new SessionResolution(true, session, null);
    }

    public static SessionResolution terminated(String reason) {
        return new SessionResolution(false, null, reason);
    }
}
