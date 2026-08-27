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

/**
 * {@code follow_player} — start persistent follow behavior. The call returns
 * immediately; following continues until {@code stop_following} or the world
 * state changes. No new LLM call is needed to keep following.
 */
public final class FollowPlayerTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "follow_player",
            "Start following the named player at a distance until told to stop.",
            List.of(
                    new ParamSpec("player", ParamType.STRING, "Name of the player to follow.", true, null, null, null, null),
                    new ParamSpec("distance", ParamType.NUMBER,
                            "Distance to keep from the player.", false, 1d, 32d, null, 4d)),
            false, true, Duration.ofSeconds(30), SecurityClass.STANDARD);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        String player = String.valueOf(call.arguments().get("player"));
        double distance = asDouble(call.arguments().get("distance"), 4d);
        return WorldToolSupport.fromAction(DEF, context.world().followPlayer(player, distance));
    }

    private static double asDouble(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        return def;
    }
}
