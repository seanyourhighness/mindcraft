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
import java.util.List;

/** {@code equip_item} — equip an item the companion is carrying. */
public final class EquipItemTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "equip_item",
            "Equip an item the companion is carrying.",
            List.of(new ParamSpec("item", ParamType.STRING,
                    "Item id (e.g. \"diamond_pickaxe\").", true, null, null, null, null)),
            false, false, Duration.ofSeconds(10), SecurityClass.STANDARD);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        String item = String.valueOf(call.arguments().get("item"));
        return WorldToolSupport.fromAction(DEF, context.world().equipItem(item));
    }
}
