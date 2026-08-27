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

/** {@code consume_item} — eat/drink/use an item from the inventory. */
public final class ConsumeItemTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "consume_item",
            "Consume (eat/drink/use) an item from the companion's inventory.",
            List.of(
                    new ParamSpec("item", ParamType.STRING,
                            "Item id (e.g. \"cooked_beef\").", true, null, null, null, null),
                    new ParamSpec("count", ParamType.INTEGER,
                            "How many to consume.", false, 1d, 64d, null, 1L)),
            false, false, Duration.ofSeconds(10), SecurityClass.STANDARD);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        String item = String.valueOf(call.arguments().get("item"));
        int count = asInt(call.arguments().get("count"), 1);
        return WorldToolSupport.fromAction(DEF, context.world().removeItem(item, count));
    }

    private static int asInt(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        return def;
    }
}
