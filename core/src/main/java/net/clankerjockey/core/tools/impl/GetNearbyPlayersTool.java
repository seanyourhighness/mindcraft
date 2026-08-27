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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code get_nearby_players} — names of players within a radius. */
public final class GetNearbyPlayersTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "get_nearby_players",
            "List the names of players within a radius of the companion.",
            List.of(new ParamSpec("radius", ParamType.NUMBER,
                    "Search radius in blocks.", false, 1d, 256d, null, 32d)),
            true, false, Duration.ZERO, SecurityClass.QUERY);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        double radius = asDouble(call.arguments().get("radius"), 32d);
        List<String> players = context.world().nearbyPlayers(radius);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("players", players);
        return ToolResult.success(DEF.name(),
                players.isEmpty() ? "No players nearby." : players.size() + " player(s) nearby.", data);
    }

    private static double asDouble(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        return def;
    }
}
