package net.mindcraft.core.tools;

import net.mindcraft.core.agent.AgentContext;

/**
 * A deterministic, schema-constrained capability the companion can invoke.
 *
 * <p>Tools never execute arbitrary model-provided code; every tool is a
 * reviewed Java implementation that validates its own inputs via the shared
 * schema before touching the world.</p>
 */
public interface Tool {

    /** Immutable schema: name, description, params, policy flags. */
    ToolDefinition definition();

    /**
     * Execute the tool. Implementations must tolerate null/absent arguments
     * (the validator has already normalized them) and return a structured
     * {@link ToolResult} rather than throwing for expected failures.
     *
     * @throws ToolException for unexpected/internal errors
     */
    ToolResult execute(ToolCall call, AgentContext context) throws ToolException;
}
