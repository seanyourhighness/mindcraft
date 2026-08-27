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

/** {@code give_item} — hand items from the companion's inventory to a player. */
public final class GiveItemTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "give_item",
            "Give items to a player from the companion's inventory (removes them from the inventory).",
            List.of(
                    new ParamSpec("player", ParamType.STRING,
                            "Name of the player to give to.", true, null, null, null, null),
                    new ParamSpec("item", ParamType.STRING,
                            "Item id (e.g. \"cooked_beef\").", true, null, null, null, null),
                    new ParamSpec("count", ParamType.INTEGER,
                            "How many to give.", false, 1d, 64d, null, 1L)),
            false, true, Duration.ofSeconds(20), SecurityClass.STANDARD);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        String player = String.valueOf(call.arguments().get("player"));
        String item = String.valueOf(call.arguments().get("item"));
        int count = asInt(call.arguments().get("count"), 1);
        return WorldToolSupport.fromAction(DEF, context.world().giveItemToPlayer(player, item, count));
    }

    private static int asInt(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        return def;
    }
}
