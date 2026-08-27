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

/** {@code go_to_coordinates} — walk the companion to an explicit position. */
public final class GoToCoordinatesTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "go_to_coordinates",
            "Walk to the given x/y/z coordinates and stop at the given closeness.",
            List.of(
                    new ParamSpec("x", ParamType.NUMBER, "X coordinate.", true, -30_000_000d, 30_000_000d, null, null),
                    new ParamSpec("y", ParamType.NUMBER, "Y coordinate.", true, -64d, 320d, null, null),
                    new ParamSpec("z", ParamType.NUMBER, "Z coordinate.", true, -30_000_000d, 30_000_000d, null, null),
                    new ParamSpec("closeness", ParamType.NUMBER,
                            "How close to get (blocks).", false, 0d, 64d, null, 2d)),
            false, true, Duration.ofSeconds(60), SecurityClass.STANDARD);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        double x = asDouble(call.arguments().get("x"), 0);
        double y = asDouble(call.arguments().get("y"), 0);
        double z = asDouble(call.arguments().get("z"), 0);
        double closeness = asDouble(call.arguments().get("closeness"), 2d);
        return WorldToolSupport.fromAction(DEF, context.world().goTo(x, y, z, closeness));
    }

    private static double asDouble(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        return def;
    }
}
