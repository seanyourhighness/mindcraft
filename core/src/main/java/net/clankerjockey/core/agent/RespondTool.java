package net.clankerjockey.core.agent;

import net.clankerjockey.core.tools.ParamSpec;
import net.clankerjockey.core.tools.ParamType;
import net.clankerjockey.core.tools.SecurityClass;
import net.clankerjockey.core.tools.Tool;
import net.clankerjockey.core.tools.ToolCall;
import net.clankerjockey.core.tools.ToolDefinition;
import net.clankerjockey.core.tools.ToolResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Synthetic tool representing the companion's final natural-language answer.
 * The agent loop intercepts {@code respond} calls before execution and treats
 * {@code arguments.text} as the turn's final output, so every model response
 * (tool call or speech) shares one constrained JSON format.
 */
public final class RespondTool implements Tool {

    public static final String NAME = "respond";

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            NAME,
            "Speak to the player in character. Use this as your final message.",
            List.of(new ParamSpec("text", ParamType.STRING,
                    "What you say, 1-2 sentences, fully in character.", true,
                    null, null, null, null)),
            true, false, Duration.ZERO, SecurityClass.SYSTEM);

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        Object text = call.arguments().get("text");
        return ToolResult.success(NAME, "responded",
                Map.of("text", text instanceof String s ? s : ""));
    }
}
