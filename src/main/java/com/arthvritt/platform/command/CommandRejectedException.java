package com.arthvritt.platform.command;

import com.arthvritt.platform.shared.error.PlatformException;
import org.springframework.http.HttpStatus;

/**
 * A command was rejected at the control boundary <i>before</i> (or instead of) producing its business
 * envelope. The {@code errorCode} is the B4 §4.2 machine reason; the HTTP status is the one B4 maps it
 * to. These rejects emit <b>no</b> audit envelope and, because they propagate out of the gateway's
 * transaction, roll back any idempotency claim (INV-3/INV-4/INV-6). The 422 invariant /
 * maker-checker (M4d) reject paths, which <i>do</i> emit a {@code CommandRejected} envelope, are
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

    /**
     * The checker equals the maker on the same record (C4/X11). 409, no envelope here — the
     * {@code MakerChecker.Blocked} <i>envelope-emitting</i> variant lands at WS-4.
     */
    public static CommandRejectedException checkerEqualsMaker() {
        return new CommandRejectedException("checker_equals_maker",
                "the checker cannot be the maker on this record", HttpStatus.CONFLICT);
    }

    /** The actor holds none of the roles the command requires (C18). 403, no envelope (G22). */
    public static CommandRejectedException roleNotHeld() {
        return new CommandRejectedException("role_not_held",
                "actor does not hold a role authorised for this command", HttpStatus.FORBIDDEN);
    }

    /** The assignment forms a strict SoD pair the policy system-blocks (C5). 403, no envelope (G22). */
    public static CommandRejectedException sodRoleBlock(String role, String conflictsWith) {
        return new CommandRejectedException("sod_role_block",
                "role " + role + " is strictly segregated from " + conflictsWith, HttpStatus.FORBIDDEN);
    }

    /** A concurrent first execution of this {@code command_id} is still in flight. 409, retryable. */
    public static CommandRejectedException commandInProgress() {
        return new CommandRejectedException("command_in_progress",
                "a command with this id is currently being processed", HttpStatus.CONFLICT);
    }

    /**
     * Not all DL-027 operational checks have been recorded yet (INV.5). 422 — the caller must record
     * the missing checks and then retry {@code complete-ops-checks}. No state change occurs.
     */
    public static CommandRejectedException operationalChecksIncomplete(String missing) {
        return new CommandRejectedException("operational_checks_incomplete",
                "operational checks incomplete; missing: " + missing, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Override
    public HttpStatus getStatus() {
        return status;
    }
}
