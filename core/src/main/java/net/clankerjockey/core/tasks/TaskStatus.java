package net.clankerjockey.core.tasks;

/**
 * Lifecycle states for long-running companion actions, matching the goal
 * document's ActionManager states. Terminal states can never be left, so a
 * cancelled task stays cancelled.
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    BLOCKED,
    INTERRUPTED,
    CANCELLED,
    TIMED_OUT;

    public boolean isTerminal() {
        return this != PENDING && this != RUNNING;
    }

    /** Lowercase name for JSON payloads (matches ToolResult.status). */
    public String jsonName() {
        return name().toLowerCase();
    }
}
