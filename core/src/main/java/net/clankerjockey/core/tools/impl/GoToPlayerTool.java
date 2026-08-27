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

/** {@code go_to_player} — walk the companion to a named player. */
public final class GoToPlayerTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "go_to_player",
            "Walk to the named player and stop at the given closeness.",
            List.of(
                    new ParamSpec("player", ParamType.STRING, "Name of the player to go to.", true, null, null, null, null),
                    new ParamSpec("closeness", ParamType.NUMBER,
                            "How close to get (blocks).", false, 0d, 64d, null, 2d)),
            false, true, Duration.ofSeconds(60), SecurityClass.STANDARD);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        String player = String.valueOf(call.arguments().get("player"));
        double closeness = asDouble(call.arguments().get("closeness"), 2d);
        return WorldToolSupport.fromAction(DEF, context.world().goToPlayer(player, closeness));
    }

    private static double asDouble(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        return def;
    }
}
