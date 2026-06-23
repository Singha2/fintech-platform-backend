package com.arthvritt.platform.infrastructure.security;

import com.arthvritt.platform.auth.SessionResolution;
import com.arthvritt.platform.auth.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Resolves the {@code Authorization: Bearer <sessionId>} credential into an authenticated principal
 * (the {@link com.arthvritt.platform.auth.AuthSession}) — WS-0's edge of M3b's session substrate
 * (DL-BE-030, INV-1). The bearer is the opaque {@code auth_session.session_id}; every other field is
 * loaded server-side via {@link SessionService#resolveSession}, never trusted from the client.
 *
 * <p>The filter only <i>authenticates</i> — it never authorises. Role/SoD/MFA gating stays at the command
 * boundary ({@code CommandGateway}). A missing or non-live bearer leaves the context unauthenticated; the
 * {@link BearerAuthenticationEntryPoint} renders the B4 401 (it reads the reason this filter stashes).
 * Constructed by {@code SecurityConfig} (not a {@code @Component}) so it is not also auto-registered as a
 * plain servlet filter.
 */
public class SessionBearerAuthFilter extends OncePerRequestFilter {

    static final String AUTH_ERROR_ATTR = "ws.authErrorCode";
    private static final String BEARER = "Bearer ";

    private final SessionService sessions;

    public SessionBearerAuthFilter(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            String token = header.substring(BEARER.length()).trim();
            try {
                SessionResolution resolution = sessions.resolveSession(UUID.fromString(token));
                if (resolution.active()) {
                    PreAuthenticatedAuthenticationToken authentication =
                            new PreAuthenticatedAuthenticationToken(resolution.session(), token, List.of());
                    authentication.setAuthenticated(true);
                    // Fresh context (not the shared one) — the recommended pattern, so a live bearer never
                    // inherits or leaks state across pooled-thread requests.
                    SecurityContext context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(authentication);
                    SecurityContextHolder.setContext(context);
                } else {
                    request.setAttribute(AUTH_ERROR_ATTR, "bearer_expired"); // not_found / expired / revoked
                }
            } catch (IllegalArgumentException notAUuid) {
                request.setAttribute(AUTH_ERROR_ATTR, "bearer_invalid");
            }
        }
        filterChain.doFilter(request, response);
    }
}
