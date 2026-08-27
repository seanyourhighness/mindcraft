package net.mindcraft.core.tools.impl;

import net.mindcraft.core.agent.AgentContext;
import net.mindcraft.core.tools.ParamSpec;
import net.mindcraft.core.tools.ParamType;
import net.mindcraft.core.tools.SecurityClass;
import net.mindcraft.core.tools.Tool;
import net.mindcraft.core.tools.ToolCall;
import net.mindcraft.core.tools.ToolDefinition;
import net.mindcraft.core.tools.ToolResult;
import net.mindcraft.core.world.InventoryView;
import net.mindcraft.core.world.ItemCount;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code has_item} — does the companion carry at least one of an item? */
public final class HasItemTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "has_item",
            "Check whether the companion is carrying at least one of an item.",
            List.of(new ParamSpec("item", ParamType.STRING,
                    "Item id (e.g. \"cooked_beef\").", true, null, null, null, null)),
            true, false, Duration.ZERO, SecurityClass.QUERY);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        String item = String.valueOf(call.arguments().get("item"));
        InventoryView view = context.world().inventory();
        int count = 0;
        for (ItemCount ic : view.items()) {
            if (ic.item().equalsIgnoreCase(item)) count += ic.count();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("item", item);
        data.put("count", count);
        data.put("has", count > 0);
        return ToolResult.success(DEF.name(),
                count > 0 ? "Carrying " + count + " " + item + "." : "Not carrying " + item + ".", data);
    }
}
