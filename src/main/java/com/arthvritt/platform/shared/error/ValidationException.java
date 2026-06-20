package com.arthvritt.platform.shared.error;

import org.springframework.http.HttpStatus;

/**
 * A domain invariant or input rule was violated (e.g. negative basis points,
 * a negative amount in a positive-money context). Maps to HTTP 400.
 */
public class ValidationException extends PlatformException {

    public ValidationException(String message) {
        super("validation_failed", message);
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.BAD_REQUEST;
    }
}
