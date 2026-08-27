package net.mindcraft.core.tools.impl;

import net.mindcraft.core.agent.AgentContext;
import net.mindcraft.core.tools.ParamSpec;
import net.mindcraft.core.tools.ParamType;
import net.mindcraft.core.tools.Tool;
import net.mindcraft.core.tools.ToolCall;
import net.mindcraft.core.tools.ToolDefinition;
import net.mindcraft.core.tools.ToolResult;
import net.mindcraft.core.world.PlayerState;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code get_player_state} — compact state of a named player. */
public final class GetPlayerStateTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "get_player_state",
            "Get a named player's position, distance from the companion, health and online status.",
            List.of(new ParamSpec("player", ParamType.STRING,
                    "Name of the player to inspect.", true, null, null, null, null)),
            true, false, Duration.ZERO, net.mindcraft.core.tools.SecurityClass.QUERY);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        String name = String.valueOf(call.arguments().get("player"));
        PlayerState p = context.world().playerState(name);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("online", p.online());
        if (p.online()) {
            Map<String, Object> pos = new LinkedHashMap<>();
            pos.put("x", round2(p.x()));
            pos.put("y", round2(p.y()));
            pos.put("z", round2(p.z()));
            data.put("position", pos);
            data.put("distance", round2(p.distance()));
            data.put("health", round2(p.health()));
        }
        return ToolResult.success(DEF.name(), p.online()
                ? name + " is online." : name + " is not online.", data);
    }

    private static String round2(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }
}
