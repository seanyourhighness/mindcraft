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

/**
 * {@code attack_entity} — attack the nearest entity of a type. Owner-only:
 * combat is a privileged action enforced below the model.
 */
public final class AttackEntityTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "attack_entity",
            "Attack the nearest entity of a given type (e.g. \"zombie\"). Owner-only.",
            List.of(new ParamSpec("type", ParamType.STRING,
                    "Entity type to attack.", true, null, null, null, null)),
            false, true, Duration.ofSeconds(10), SecurityClass.PRIVILEGED);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        String type = String.valueOf(call.arguments().get("type"));
        return WorldToolSupport.fromAction(DEF, context.world().attackEntity(type));
    }
}
