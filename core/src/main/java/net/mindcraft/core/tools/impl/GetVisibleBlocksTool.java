package net.mindcraft.core.tools.impl;

import net.mindcraft.core.agent.AgentContext;
import net.mindcraft.core.tools.ParamSpec;
import net.mindcraft.core.tools.ParamType;
import net.mindcraft.core.tools.SecurityClass;
import net.mindcraft.core.tools.Tool;
import net.mindcraft.core.tools.ToolCall;
import net.mindcraft.core.tools.ToolDefinition;
import net.mindcraft.core.tools.ToolResult;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code get_visible_blocks} — distinct block types around the companion. */
public final class GetVisibleBlocksTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "get_visible_blocks",
            "List the distinct block types visible within a radius of the companion.",
            List.of(new ParamSpec("radius", ParamType.NUMBER,
                    "Search radius in blocks.", false, 1d, 32d, null, 8d)),
            true, false, Duration.ZERO, SecurityClass.QUERY);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        double radius = asDouble(call.arguments().get("radius"), 8d);
        List<String> blocks = context.world().visibleBlockTypes(radius);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("blocks", blocks);
        return ToolResult.success(DEF.name(),
                blocks.isEmpty() ? "Nothing notable nearby." : "Visible blocks: " + String.join(", ", blocks), data);
    }

    private static double asDouble(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        return def;
    }
}
