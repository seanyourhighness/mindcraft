package net.clankerjockey.core.tools.impl;

import net.clankerjockey.core.agent.AgentContext;
import net.clankerjockey.core.tools.ParamSpec;
import net.clankerjockey.core.tools.ParamType;
import net.clankerjockey.core.tools.SecurityClass;
import net.clankerjockey.core.tools.Tool;
import net.clankerjockey.core.tools.ToolCall;
import net.clankerjockey.core.tools.ToolDefinition;
import net.clankerjockey.core.tools.ToolResult;
import net.clankerjockey.core.world.PlayerState;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code get_player_distance} — distance from the companion to a player. */
public final class GetPlayerDistanceTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "get_player_distance",
            "Get the distance from the companion to a named player.",
            List.of(new ParamSpec("player", ParamType.STRING,
                    "Name of the player.", true, null, null, null, null)),
            true, false, Duration.ZERO, SecurityClass.QUERY);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        String player = String.valueOf(call.arguments().get("player"));
        PlayerState p = context.world().playerState(player);
        if (!p.online()) {
            return ToolResult.blocked(DEF.name(), "Player '" + player + "' is not online.");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("player", player);
        data.put("distance", round2(p.distance()));
        return ToolResult.success(DEF.name(),
                player + " is " + round2(p.distance()) + " blocks away.", data);
    }

    private static String round2(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }
}
