package net.clankerjockey.core.tools;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Immutable schema describing a tool: its name, description, typed
 * parameters, execution requirements and policy flags.
 *
 * @param name            unique tool name used in {@code {"tool": ...}}
 * @param description     short model-facing description of when to use it
 * @param parameters      typed parameter schema, required params first
 * @param readOnly        true when the tool never changes world/companion state
 * @param interruptible   true when the tool may be interrupted mid-execution
 * @param timeout         per-call execution budget (may be {@code Duration.ZERO}
 *                        meaning "use the executor default")
 * @param securityClass   permission class enforced by {@link ToolExecutor}
 */
public record ToolDefinition(String name, String description, List<ParamSpec> parameters,
                             boolean readOnly, boolean interruptible, Duration timeout,
                             SecurityClass securityClass) {

    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("tool description must not be blank");
        }
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        if (timeout == null || timeout.isNegative()) {
            timeout = Duration.ZERO;
        }
        Objects.requireNonNull(securityClass, "securityClass");
    }

    /** Read-only tool definition with no parameters. */
    public static ToolDefinition query(String name, String description) {
        return new ToolDefinition(name, description, List.of(), true, false,
                Duration.ZERO, SecurityClass.QUERY);
    }

    /** Standard (world-affecting) tool definition with no parameters. */
    public static ToolDefinition action(String name, String description) {
        return new ToolDefinition(name, description, List.of(), false, true,
                Duration.ZERO, SecurityClass.STANDARD);
    }
}
