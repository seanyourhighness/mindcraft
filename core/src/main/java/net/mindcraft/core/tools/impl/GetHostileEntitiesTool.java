package net.mindcraft.core.tools.impl;

import net.mindcraft.core.agent.AgentContext;
import net.mindcraft.core.tools.ParamSpec;
import net.mindcraft.core.tools.ParamType;
import net.mindcraft.core.tools.SecurityClass;
import net.mindcraft.core.tools.Tool;
import net.mindcraft.core.tools.ToolCall;
import net.mindcraft.core.tools.ToolDefinition;
import net.mindcraft.core.tools.ToolResult;
import net.mindcraft.core.world.EntityInfo;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code get_hostile_entities} — compact list of nearby monsters, exactly the
 * shape the goal doc's examples use: {@code {"hostiles":[{"type":"creeper",
 * "distance":4.2,"direction":"behind-right"}]}}.
 */
public final class GetHostileEntitiesTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "get_hostile_entities",
            "List nearby hostile mobs (creepers, zombies, ...) with distance and direction.",
            List.of(new ParamSpec("radius", ParamType.NUMBER,
                    "Search radius in blocks.", false, 1d, 64d, null, 24d)),
            true, false, Duration.ZERO, SecurityClass.QUERY);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        double radius = asDouble(call.arguments().get("radius"), 24d);
        List<Map<String, Object>> hostiles = new ArrayList<>();
        for (EntityInfo e : context.world().nearbyEntities(radius)) {
            if (!e.hostile()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", e.type());
            m.put("distance", round2(e.distance()));
            m.put("direction", e.direction());
            hostiles.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hostiles", hostiles);
        return ToolResult.success(DEF.name(),
                hostiles.isEmpty() ? "No hostiles nearby." : hostiles.size() + " hostile(s) nearby.", data);
    }

    private static double asDouble(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        return def;
    }

    private static String round2(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }
}
