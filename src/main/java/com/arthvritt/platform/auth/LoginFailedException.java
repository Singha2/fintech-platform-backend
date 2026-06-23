package com.arthvritt.platform.auth;

import com.arthvritt.platform.shared.error.PlatformException;
import org.springframework.http.HttpStatus;

/**
 * A login step failed — bad password, or a wrong/expired/locked OTP. The reason is deliberately
 * indistinguishable to the client (a single {@code bad_credentials} 401), so neither the password nor the
 * OTP leg leaks which factor was wrong. No audit envelope at the API layer (the auth service already
 * records the rejection internally).
 */
public class LoginFailedException extends PlatformException {

    public LoginFailedException() {
        super("bad_credentials", "invalid credentials");
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.UNAUTHORIZED;
    }
}
