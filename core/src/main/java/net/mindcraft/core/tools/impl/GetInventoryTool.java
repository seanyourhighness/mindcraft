package net.mindcraft.core.tools.impl;

import net.mindcraft.core.agent.AgentContext;
import net.mindcraft.core.tools.Tool;
import net.mindcraft.core.tools.ToolCall;
import net.mindcraft.core.tools.ToolDefinition;
import net.mindcraft.core.tools.ToolResult;
import net.mindcraft.core.world.InventoryView;
import net.mindcraft.core.world.ItemCount;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code get_inventory} — the companion's inventory (virtual inventory for
 * NPC-style bodies), plus equipped items.
 */
public final class GetInventoryTool implements Tool {

    private static final ToolDefinition DEF = ToolDefinition.query(
            "get_inventory",
            "List what the companion is carrying and wearing.");

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        InventoryView view = context.world().inventory();
        List<Map<String, Object>> items = new ArrayList<>();
        for (ItemCount ic : view.items()) {
            if (ic.count() <= 0) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item", ic.item());
            m.put("count", ic.count());
            items.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("equipped", view.equipped());
        return ToolResult.success(DEF.name(),
                items.isEmpty() ? "Carrying nothing." : "Carrying " + items.size() + " item type(s).", data);
    }
}
