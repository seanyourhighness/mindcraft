package net.clankerjockey.core.tools.impl;

import net.clankerjockey.core.agent.AgentContext;
import net.clankerjockey.core.tools.ParamSpec;
import net.clankerjockey.core.tools.ParamType;
import net.clankerjockey.core.tools.SecurityClass;
import net.clankerjockey.core.tools.Tool;
import net.clankerjockey.core.tools.ToolCall;
import net.clankerjockey.core.tools.ToolDefinition;
import net.clankerjockey.core.tools.ToolException;
import net.clankerjockey.core.tools.ToolResult;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/** {@code forget_place} — remove a saved place. */
public final class ForgetPlaceTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "forget_place",
            "Forget a saved place by name.",
            List.of(new ParamSpec("name", ParamType.STRING,
                    "Name of the place to forget.", true, null, null, null, null)),
            false, false, Duration.ofSeconds(10), SecurityClass.STANDARD);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        if (context.places() == null) {
            return ToolResult.failure(DEF.name(), "Place memory is unavailable.");
        }
        String name = String.valueOf(call.arguments().get("name"));
        try {
            boolean removed = context.places().forget(name);
            return removed
                    ? ToolResult.success(DEF.name(), "Forgot '" + name + "'.")
                    : ToolResult.failure(DEF.name(), "No place named '" + name + "' is saved.");
        } catch (IOException e) {
            throw new ToolException("could not forget place '" + name + "'", e);
        }
    }
}
