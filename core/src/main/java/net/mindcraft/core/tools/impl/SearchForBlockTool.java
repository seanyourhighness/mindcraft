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

/** {@code search_for_block} — find a block type and walk to it in one action. */
public final class SearchForBlockTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "search_for_block",
            "Find the nearest block of a type (e.g. \"iron_ore\") within a range and walk to it.",
            List.of(
                    new ParamSpec("block", ParamType.STRING,
                            "Block id to search for.", true, null, null, null, null),
                    new ParamSpec("range", ParamType.NUMBER,
                            "Search range in blocks.", false, 1d, 64d, null, 32d)),
            false, true, Duration.ofSeconds(60), SecurityClass.STANDARD);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        String block = String.valueOf(call.arguments().get("block"));
        double range = asDouble(call.arguments().get("range"), 32d);
        Optional<BlockInfo> found = context.world().findNearbyBlock(block, range);
        if (found.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("distance_searched", range);
            return ToolResult.blocked(DEF.name(),
                    "No " + block + " visible within " + Math.round(range) + " blocks.", data);
        }
        BlockInfo b = found.get();
        context.world().goTo(b.x(), b.y(), b.z(), 2.0D);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("block", b.block());
        Map<String, Object> pos = new LinkedHashMap<>();
        pos.put("x", b.x());
        pos.put("y", b.y());
        pos.put("z", b.z());
        data.put("position", pos);
        data.put("distance", Math.round(b.distance()));
        return ToolResult.success(DEF.name(),
                "Found " + b.block() + " " + Math.round(b.distance()) + " blocks away; heading there.", data);
    }

    private static double asDouble(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        return def;
    }
}
