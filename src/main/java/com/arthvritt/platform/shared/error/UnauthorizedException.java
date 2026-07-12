package com.arthvritt.platform.shared.error;

import org.springframework.http.HttpStatus;

/**
 * A caller failed to authenticate on a route that authenticates itself rather than via a session bearer
 * (e.g. the API-key-gated bootstrap endpoint). Maps to HTTP 401. Distinct from the edge session 401 the
 * security {@code AuthenticationEntryPoint} renders — this is thrown from inside a handler.
 */
public class UnauthorizedException extends PlatformException {

    public UnauthorizedException(String message) {
        super("unauthorized", message);
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.UNAUTHORIZED;
    }
}
