package com.arthvritt.platform.command;

import java.util.UUID;

/**
 * The gateway's return for a successfully-handled or replayed command. {@code replayed} true means the
 * command was already applied under this {@code (actor_id, command_id)} and no mutation re-ran (#4);
 * {@code eventId} then points at the <i>original</i> resulting envelope. {@code result} is null on a
 * replay (the original caller already received it).
 */
public record CommandResult<R>(R result, UUID eventId, boolean replayed) {

    public static <R> CommandResult<R> executed(R result, UUID eventId) {
        return new CommandResult<>(result, eventId, false);
    }

    public static <R> CommandResult<R> replayed(UUID eventId) {
        return new CommandResult<>(null, eventId, true);
    }
}
