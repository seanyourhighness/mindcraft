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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code search_for_entity} — find the nearest entity of a type and walk to it. */
public final class SearchForEntityTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "search_for_entity",
            "Find the nearest entity of a type (e.g. \"cow\", \"villager\") within a range and walk to it.",
            List.of(
                    new ParamSpec("type", ParamType.STRING,
                            "Entity type to search for.", true, null, null, null, null),
                    new ParamSpec("range", ParamType.NUMBER,
                            "Search range in blocks.", false, 1d, 64d, null, 32d)),
            false, true, Duration.ofSeconds(60), SecurityClass.STANDARD);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        String type = String.valueOf(call.arguments().get("type"));
        double range = asDouble(call.arguments().get("range"), 32d);
        EntityInfo best = context.world().nearbyEntities(range).stream()
                .filter(e -> e.type().equalsIgnoreCase(type))
                .min(Comparator.comparingDouble(EntityInfo::distance))
                .orElse(null);
        if (best == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("distance_searched", range);
            return ToolResult.blocked(DEF.name(),
                    "No " + type + " within " + Math.round(range) + " blocks.", data);
        }
        context.world().goTo(best.x(), best.y(), best.z(), 2.0D);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", best.type());
        data.put("distance", Math.round(best.distance()));
        data.put("direction", best.direction());
        return ToolResult.success(DEF.name(),
                "Found " + best.type() + " " + Math.round(best.distance()) + " blocks away; heading there.", data);
    }

    private static double asDouble(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        return def;
    }
}
