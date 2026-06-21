package com.arthvritt.platform.command;

import com.arthvritt.platform.shared.error.PlatformException;
import org.springframework.http.HttpStatus;

/**
 * A command was rejected at the control boundary <i>before</i> (or instead of) producing its business
 * envelope. The {@code errorCode} is the B4 §4.2 machine reason; the HTTP status is the one B4 maps it
 * to. These rejects emit <b>no</b> audit envelope and, because they propagate out of the gateway's
 * transaction, roll back any idempotency claim (INV-3/INV-4/INV-6). The 422 invariant /
 * maker-checker (M4c) reject paths, which <i>do</i> emit a {@code CommandRejected} envelope, are
 * modelled separately.
 */
public class CommandRejectedException extends PlatformException {

    private final HttpStatus status;

    private CommandRejectedException(String errorCode, String message, HttpStatus status) {
        super(errorCode, message);
        this.status = status;
    }

    /** No assertion on an admin command (C7, AU10.3). 401, no envelope. */
    public static CommandRejectedException mfaMissing() {
        return new CommandRejectedException("mfa_assertion_missing",
                "command requires a fresh MFA assertion", HttpStatus.UNAUTHORIZED);
    }

    /** Assertion present but older than the action's freshness window (B4 §6.4). 401, no envelope. */
    public static CommandRejectedException mfaExpired() {
        return new CommandRejectedException("mfa_assertion_expired",
                "MFA assertion is no longer fresh for this action", HttpStatus.UNAUTHORIZED);
    }

    /** Expected {@code aggregate_version} did not match the row (B3 P8). 409, no envelope. */
    public static CommandRejectedException versionConflict(int expected, int actual) {
        return new CommandRejectedException("aggregate_version_stale",
                "expected aggregate_version " + expected + " but found " + actual, HttpStatus.CONFLICT);
    }

    /** Same {@code command_id} reused for a different command (G18/G32). 409, no envelope. */
    public static CommandRejectedException idempotencyConflict() {
        return new CommandRejectedException("command_id_payload_mismatch",
                "command_id already used for a different command", HttpStatus.CONFLICT);
    }

    /** A concurrent first execution of this {@code command_id} is still in flight. 409, retryable. */
    public static CommandRejectedException commandInProgress() {
        return new CommandRejectedException("command_in_progress",
                "a command with this id is currently being processed", HttpStatus.CONFLICT);
    }

    @Override
    public HttpStatus getStatus() {
        return status;
    }
}
