package com.arthvritt.platform.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Base of the platform's domain error model (M1a).
 *
 * <p>Every domain exception carries a stable {@code errorCode} (machine-readable,
 * safe to expose to clients) and the {@link HttpStatus} it maps to at the API
 * boundary. {@link com.arthvritt.platform.infrastructure.web.GlobalExceptionHandler}
 * turns these into RFC 7807 problem documents; subclasses pick the status.
 */
public abstract class PlatformException extends RuntimeException {

    private final String errorCode;

    protected PlatformException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected PlatformException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    /** HTTP status this exception maps to at the API boundary. */
    public abstract HttpStatus getStatus();
}
