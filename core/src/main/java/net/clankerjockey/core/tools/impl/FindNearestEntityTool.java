package net.clankerjockey.core.tools.impl;

import net.clankerjockey.core.agent.AgentContext;
import net.clankerjockey.core.tools.ParamSpec;
import net.clankerjockey.core.tools.ParamType;
import net.clankerjockey.core.tools.SecurityClass;
import net.clankerjockey.core.tools.Tool;
import net.clankerjockey.core.tools.ToolCall;
import net.clankerjockey.core.tools.ToolDefinition;
import net.clankerjockey.core.tools.ToolResult;
import net.clankerjockey.core.world.EntityInfo;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code find_nearest_entity} — nearest entity of a given type within a radius. */
public final class FindNearestEntityTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "find_nearest_entity",
            "Find the nearest entity of a given type (e.g. \"cow\", \"zombie\", \"villager\").",
            List.of(
                    new ParamSpec("type", ParamType.STRING,
                            "Entity type to look for.", true, null, null, null, null),
                    new ParamSpec("radius", ParamType.NUMBER,
                            "Search radius in blocks.", false, 1d, 64d, null, 32d)),
            true, false, Duration.ZERO, SecurityClass.QUERY);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        String type = String.valueOf(call.arguments().get("type"));
        double radius = asDouble(call.arguments().get("radius"), 32d);
        EntityInfo best = context.world().nearbyEntities(radius).stream()
                .filter(e -> e.type().equalsIgnoreCase(type))
                .min(Comparator.comparingDouble(EntityInfo::distance))
                .orElse(null);
        if (best == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("distance_searched", radius);
            return ToolResult.blocked(DEF.name(),
                    "No " + type + " within " + round2(radius) + " blocks.", data);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", best.type());
        data.put("distance", round2(best.distance()));
        data.put("direction", best.direction());
        return ToolResult.success(DEF.name(),
                "Nearest " + best.type() + " is " + round2(best.distance()) + " blocks away.", data);
    }

    private static double asDouble(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        return def;
    }

    private static String round2(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }
}
