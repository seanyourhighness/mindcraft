package net.mindcraft.core.tools.impl;

import net.mindcraft.core.agent.AgentContext;
import net.mindcraft.core.tools.ParamSpec;
import net.mindcraft.core.tools.ParamType;
import net.mindcraft.core.tools.SecurityClass;
import net.mindcraft.core.tools.Tool;
import net.mindcraft.core.tools.ToolCall;
import net.mindcraft.core.tools.ToolDefinition;
import net.mindcraft.core.tools.ToolException;
import net.mindcraft.core.tools.ToolResult;
import net.mindcraft.core.world.InventoryView;
import net.mindcraft.core.world.ItemCount;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code put_in_container} — move items from the inventory into a container. */
public final class PutInContainerTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "put_in_container",
            "Move items from the companion's inventory into an open container.",
            List.of(
                    new ParamSpec("name", ParamType.STRING,
                            "Container name.", true, null, null, null, null),
                    new ParamSpec("item", ParamType.STRING,
                            "Item id.", true, null, null, null, null),
                    new ParamSpec("count", ParamType.INTEGER,
                            "How many to move.", false, 1d, 64d, null, 1L)),
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
        if (context.containers().view(name).isEmpty()) {
            return ToolResult.blocked(DEF.name(), "No container named '" + name + "' is open; open it first.");
        }
        InventoryView inv = context.world().inventory();
        int have = 0;
        for (ItemCount ic : inv.items()) {
            if (ic.item().equalsIgnoreCase(item)) have += ic.count();
        }
        if (have < count) {
            return ToolResult.blocked(DEF.name(), "Not carrying enough " + item
                    + " (have " + have + ", need " + count + ").");
        }
        try {
            var removed = context.world().removeItem(item, count);
            if (!removed.ok()) {
                return ToolResult.failure(DEF.name(), removed.message());
            }
            int total = context.containers().put(name, item, count);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("container", name);
            data.put("item", item);
            data.put("count", count);
            data.put("total_in_container", total);
            return ToolResult.success(DEF.name(),
                    "Put " + count + " " + item + " in '" + name + "'.", data);
        } catch (IOException e) {
            throw new ToolException("could not update container '" + name + "'", e);
        }
    }

    private static int asInt(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        return def;
    }
}
