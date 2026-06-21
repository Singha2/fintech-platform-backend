package com.arthvritt.platform.adminiam;

/**
 * The outcome of evaluating a role assignment against the current SoD policy (C5, DL-033).
 * {@link #STRICT_BLOCK} is system-blocked; {@link #SOFT_WARN} is allowed only with a logged override;
 * {@link #CLEAR} has no conflict.
 */
public enum SodDecision {
    STRICT_BLOCK,
    SOFT_WARN,
    CLEAR
}
