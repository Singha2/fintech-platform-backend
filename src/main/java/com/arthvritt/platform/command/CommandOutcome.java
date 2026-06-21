package com.arthvritt.platform.command;

/**
 * A {@link CommandHandler}'s return: the caller-facing {@code result} plus the {@link CommandEvent}
 * the gateway will audit. {@code result} may be {@code null} for commands that return nothing.
 */
public record CommandOutcome<R>(R result, CommandEvent event) {
}
