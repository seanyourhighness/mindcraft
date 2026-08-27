package net.mindcraft.core.agent;

import net.mindcraft.core.tools.ToolCall;
import net.mindcraft.core.tools.ToolResult;

/** One executed tool call within a turn, for observability and tests. */
public record ToolTrace(ToolCall call, ToolResult result) {

    public String signature() {
        return call.signature();
    }
}
