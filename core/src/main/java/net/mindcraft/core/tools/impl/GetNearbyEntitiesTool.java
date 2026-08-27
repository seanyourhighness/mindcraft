package net.mindcraft.core.tools.impl;

import net.mindcraft.core.agent.AgentContext;
import net.mindcraft.core.tools.ParamSpec;
import net.mindcraft.core.tools.ParamType;
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
 * {@code get_nearby_entities} — compact list of nearby entities with type,
 * distance and direction, capped so prompts never receive giant entity dumps.
 */
public final class GetNearbyEntitiesTool implements Tool {

    static final int MAX_ENTITIES = 12;

    private static final ToolDefinition DEF = new ToolDefinition(
            "get_nearby_entities",
            "List nearby entities (players, mobs) with type, distance and direction.",
            List.of(new ParamSpec("radius", ParamType.NUMBER,
                    "Search radius in blocks.", false, 1d, 128d, null, 32d)),
            true, false, Duration.ZERO, net.mindcraft.core.tools.SecurityClass.QUERY);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        double radius = asDouble(call.arguments().get("radius"), 32d);
        List<EntityInfo> found = context.world().nearbyEntities(radius);
        List<Map<String, Object>> entities = new ArrayList<>();
        int shown = 0;
        for (EntityInfo e : found) {
            if (shown >= MAX_ENTITIES) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", e.type());
            m.put("distance", round2(e.distance()));
            m.put("direction", e.direction());
            if (e.hostile()) m.put("hostile", true);
            entities.add(m);
            shown++;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entities", entities);
        if (found.size() > shown) {
            data.put("truncated", found.size() - shown);
        }
        return ToolResult.success(DEF.name(), "Found " + found.size() + " entit" + (found.size() == 1 ? "y" : "ies")
                + " within " + round2(radius) + " blocks.", data);
    }

    private static double asDouble(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        return def;
    }

    private static String round2(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }
}
