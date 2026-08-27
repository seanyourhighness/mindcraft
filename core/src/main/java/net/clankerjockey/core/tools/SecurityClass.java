package net.clankerjockey.core.tools;

/**
 * Permission/security class of a tool. Authorization is enforced by the
 * {@link ToolExecutor} below the model: the LLM never decides permissions.
 */
public enum SecurityClass {
    /** Read-only observation of the world/self; safe for anyone. */
    QUERY,

    /** Routine world action (movement, following). */
    STANDARD,

    /** Requires owner authorization (inventory transfers, combat). */
    PRIVILEGED,

    /** Internal plumbing, never directly player-visible. */
    SYSTEM
}
