package com.arthvritt.platform.shared.error;

import org.springframework.http.HttpStatus;

/**
 * The addressed aggregate / resource does not exist. Maps to HTTP 404 with the B4 error body — so a read
 * of an unknown id is distinguishable from a real server fault, and does not log an ERROR with a stack
 * trace the way an uncaught {@code EmptyResultDataAccessException} would.
 */
public class NotFoundException extends PlatformException {

    public NotFoundException(String message) {
        super("not_found", message);
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.NOT_FOUND;
    }
}
