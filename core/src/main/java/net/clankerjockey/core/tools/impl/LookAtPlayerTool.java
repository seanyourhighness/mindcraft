package net.clankerjockey.core.tools.impl;

import net.clankerjockey.core.agent.AgentContext;
import net.clankerjockey.core.tools.ParamSpec;
import net.clankerjockey.core.tools.ParamType;
import net.clankerjockey.core.tools.SecurityClass;
import net.clankerjockey.core.tools.Tool;
import net.clankerjockey.core.tools.ToolCall;
import net.clankerjockey.core.tools.ToolDefinition;
import net.clankerjockey.core.tools.ToolResult;

import java.time.Duration;
import java.util.List;

/** {@code look_at} — turn the companion toward a player. */
public final class LookAtPlayerTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "look_at",
            "Turn the companion's head toward a named player.",
            List.of(new ParamSpec("player", ParamType.STRING,
                    "Name of the player to look at.", true, null, null, null, null)),
            false, false, Duration.ofSeconds(10), SecurityClass.STANDARD);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        String player = String.valueOf(call.arguments().get("player"));
        return WorldToolSupport.fromAction(DEF, context.world().lookAtPlayer(player));
    }
}
