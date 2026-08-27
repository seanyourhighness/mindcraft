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

/** {@code move_away} — walk away from the current position. */
public final class MoveAwayTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "move_away",
            "Walk away from the companion's current position by a distance.",
            List.of(new ParamSpec("distance", ParamType.NUMBER,
                    "How far to move away (blocks).", false, 1d, 64d, null, 8d)),
            false, true, Duration.ofSeconds(30), SecurityClass.STANDARD);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        double distance = asDouble(call.arguments().get("distance"), 8d);
        return WorldToolSupport.fromAction(DEF, context.world().moveAway(distance));
    }

    private static double asDouble(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        return def;
    }
}
