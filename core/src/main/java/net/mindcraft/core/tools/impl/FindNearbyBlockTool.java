package net.mindcraft.core.tools.impl;

import net.mindcraft.core.agent.AgentContext;
import net.mindcraft.core.tools.ParamSpec;
import net.mindcraft.core.tools.ParamType;
import net.mindcraft.core.tools.SecurityClass;
import net.mindcraft.core.tools.Tool;
import net.mindcraft.core.tools.ToolCall;
import net.mindcraft.core.tools.ToolDefinition;
import net.mindcraft.core.tools.ToolResult;
import net.mindcraft.core.world.BlockInfo;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code find_nearby_block} — locate the nearest block of a given type
 * (e.g. {@code "iron_ore"}) within a radius. The flagship grounding query:
 * "No iron ore visible within 32 blocks." lets the model decide what to do
 * next instead of guessing.
 */
public final class FindNearbyBlockTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "find_nearby_block",
            "Find the nearest block of a given type (e.g. \"iron_ore\", \"oak_log\") within a radius.",
            List.of(
                    new ParamSpec("block", ParamType.STRING,
                            "Block id to search for.", true, null, null, null, null),
                    new ParamSpec("radius", ParamType.NUMBER,
                            "Search radius in blocks.", false, 1d, 64d, null, 32d)),
            true, false, Duration.ofSeconds(20), SecurityClass.QUERY);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        String block = String.valueOf(call.arguments().get("block"));
        double radius = asDouble(call.arguments().get("radius"), 32d);
        Optional<BlockInfo> found = context.world().findNearbyBlock(block, radius);
        if (found.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("distance_searched", radius);
            return ToolResult.blocked(DEF.name(),
                    "No " + block + " visible within " + round2(radius) + " blocks.", data);
        }
        BlockInfo b = found.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("block", b.block());
        Map<String, Object> pos = new LinkedHashMap<>();
        pos.put("x", b.x());
        pos.put("y", b.y());
        pos.put("z", b.z());
        data.put("position", pos);
        data.put("distance", round2(b.distance()));
        return ToolResult.success(DEF.name(),
                "Found " + b.block() + " " + round2(b.distance()) + " blocks away.", data);
    }

    private static double asDouble(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        return def;
    }

    private static String round2(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }
}
