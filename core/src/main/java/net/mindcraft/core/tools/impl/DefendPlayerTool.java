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

/** {@code defend_player} — attack hostiles near a player to protect them. */
public final class DefendPlayerTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "defend_player",
            "Attack hostile mobs near a player to protect them.",
            List.of(
                    new ParamSpec("player", ParamType.STRING,
                            "Name of the player to defend.", true, null, null, null, null),
                    new ParamSpec("distance", ParamType.NUMBER,
                            "How close hostiles must be (blocks).", false, 1d, 32d, null, 12d)),
            false, true, Duration.ofSeconds(10), SecurityClass.STANDARD);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        String player = String.valueOf(call.arguments().get("player"));
        double distance = asDouble(call.arguments().get("distance"), 12d);
        return WorldToolSupport.fromAction(DEF, context.world().defendPlayer(player, distance));
    }

    private static double asDouble(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        return def;
    }
}
