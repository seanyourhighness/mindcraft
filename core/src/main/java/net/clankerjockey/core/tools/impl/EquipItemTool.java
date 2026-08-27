package net.clankerjockey.core.tools.impl;

import net.clankerjockey.core.agent.AgentContext;
import net.clankerjockey.core.tools.ParamSpec;
import net.clankerjockey.core.tools.ParamType;
import net.clankerjockey.core.tools.SecurityClass;
import net.clankerjockey.core.tools.Tool;
import net.clankerjockey.core.tools.ToolCall;
import net.clankerjockey.core.tools.ToolDefinition;
import net.clankerjockey.core.tools.ToolResult;

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
