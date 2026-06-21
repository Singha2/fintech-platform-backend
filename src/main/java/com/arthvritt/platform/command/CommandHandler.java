package com.arthvritt.platform.command;

/**
 * The body of a single command: performs the version-checked mutation and returns the result plus the
 * event to audit. Runs inside the gateway's transaction, <i>after</i> idempotency + MFA gating, so a
 * thrown {@link CommandRejectedException} (or any exception) rolls back the idempotency claim, the
 * mutation, and any envelope — leaving no trace (INV-6). It must throw
 * {@link CommandRejectedException#versionConflict} when the optimistic-lock guard matches no row.
 */
@FunctionalInterface
public interface CommandHandler<R> {

    CommandOutcome<R> handle();
}
