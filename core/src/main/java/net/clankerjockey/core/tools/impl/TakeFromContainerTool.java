package net.clankerjockey.core.tools.impl;

import net.clankerjockey.core.agent.AgentContext;
import net.clankerjockey.core.tools.ParamSpec;
import net.clankerjockey.core.tools.ParamType;
import net.clankerjockey.core.tools.SecurityClass;
import net.clankerjockey.core.tools.Tool;
import net.clankerjockey.core.tools.ToolCall;
import net.clankerjockey.core.tools.ToolDefinition;
import net.clankerjockey.core.tools.ToolException;
import net.clankerjockey.core.tools.ToolResult;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code take_from_container} — move items from a container into the inventory. */
public final class TakeFromContainerTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "take_from_container",
            "Take items from an open container into the companion's inventory.",
            List.of(
                    new ParamSpec("name", ParamType.STRING,
                            "Container name.", true, null, null, null, null),
                    new ParamSpec("item", ParamType.STRING,
                            "Item id.", true, null, null, null, null),
                    new ParamSpec("count", ParamType.INTEGER,
                            "How many to take.", false, 1d, 64d, null, 1L)),
            false, true, Duration.ofSeconds(10), SecurityClass.STANDARD);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        if (context.containers() == null) {
            return ToolResult.failure(DEF.name(), "Container storage is unavailable.");
        }
        String name = String.valueOf(call.arguments().get("name"));
        String item = String.valueOf(call.arguments().get("item"));
        int count = asInt(call.arguments().get("count"), 1);
        try {
            int taken = context.containers().take(name, item, count);
            if (taken < 0) {
                return ToolResult.blocked(DEF.name(), "No container named '" + name + "' is open.");
            }
            if (taken == 0) {
                return ToolResult.blocked(DEF.name(), "'" + name + "' holds no " + item + ".");
            }
            var added = context.world().addItem(item, taken);
            if (!added.ok()) {
                return ToolResult.failure(DEF.name(), added.message());
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("container", name);
            data.put("item", item);
            data.put("count", taken);
            return ToolResult.success(DEF.name(),
                    "Took " + taken + " " + item + " from '" + name + "'.", data);
        } catch (IOException e) {
            throw new ToolException("could not update container '" + name + "'", e);
        }
    }

    private static int asInt(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        return def;
    }
}
