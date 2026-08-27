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

/** {@code drop_item} — drop items from the inventory as an in-world entity. */
public final class DropItemTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "drop_item",
            "Drop items from the companion's inventory on the ground.",
            List.of(
                    new ParamSpec("item", ParamType.STRING,
                            "Item id (e.g. \"diamond\").", true, null, null, null, null),
                    new ParamSpec("count", ParamType.INTEGER,
                            "How many to drop.", false, 1d, 64d, null, 1L)),
            false, true, Duration.ofSeconds(20), SecurityClass.STANDARD);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        String item = String.valueOf(call.arguments().get("item"));
        int count = asInt(call.arguments().get("count"), 1);
        return WorldToolSupport.fromAction(DEF, context.world().dropItem(item, count));
    }

    private static int asInt(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        return def;
    }
}
