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

/** {@code flee_from_entity} — walk away from the nearest entity of a type. */
public final class FleeFromEntityTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "flee_from_entity",
            "Walk away from the nearest entity of a given type (e.g. \"creeper\").",
            List.of(
                    new ParamSpec("type", ParamType.STRING,
                            "Entity type to flee from.", true, null, null, null, null),
                    new ParamSpec("distance", ParamType.NUMBER,
                            "How far to flee (blocks).", false, 1d, 64d, null, 16d)),
            false, true, Duration.ofSeconds(30), SecurityClass.STANDARD);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        String type = String.valueOf(call.arguments().get("type"));
        double distance = asDouble(call.arguments().get("distance"), 16d);
        return WorldToolSupport.fromAction(DEF, context.world().fleeFromEntity(type, distance));
    }

    private static double asDouble(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        return def;
    }
}
