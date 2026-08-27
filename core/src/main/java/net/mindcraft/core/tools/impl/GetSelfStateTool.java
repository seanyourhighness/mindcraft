package net.mindcraft.core.tools.impl;

import net.mindcraft.core.agent.AgentContext;
import net.mindcraft.core.tools.Tool;
import net.mindcraft.core.tools.ToolCall;
import net.mindcraft.core.tools.ToolDefinition;
import net.mindcraft.core.tools.ToolResult;
import net.mindcraft.core.world.SelfState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code get_self_state} — compact companion state: position, dimension,
 * biome, time, weather, health, hunger, current following status.
 */
public final class GetSelfStateTool implements Tool {

    private static final ToolDefinition DEF = ToolDefinition.query(
            "get_self_state",
            "Get the companion's current state: position, dimension, biome, time, weather, health, hunger and follow status.");

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        SelfState s = context.world().selfState();
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> pos = new LinkedHashMap<>();
        pos.put("x", round2(s.x()));
        pos.put("y", round2(s.y()));
        pos.put("z", round2(s.z()));
        data.put("position", pos);
        data.put("dimension", s.dimension());
        data.put("biome", s.biome());
        data.put("time", s.timeOfDay());
        data.put("weather", s.weather());
        data.put("health", round2(s.health()));
        data.put("hunger", round2(s.hunger()));
        data.put("game_mode", s.gameMode());
        if (s.followingPlayer() != null && !s.followingPlayer().isBlank()) {
            data.put("following", s.followingPlayer());
        }
        return ToolResult.success(DEF.name(), "Self state retrieved.", data);
    }

    private static String round2(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }
}
