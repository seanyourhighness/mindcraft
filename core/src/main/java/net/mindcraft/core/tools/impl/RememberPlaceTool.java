package net.mindcraft.core.tools.impl;

import net.mindcraft.core.agent.AgentContext;
import net.mindcraft.core.tools.ParamSpec;
import net.mindcraft.core.tools.ParamType;
import net.mindcraft.core.tools.SecurityClass;
import net.mindcraft.core.tools.Tool;
import net.mindcraft.core.tools.ToolCall;
import net.mindcraft.core.tools.ToolDefinition;
import net.mindcraft.core.tools.ToolException;
import net.mindcraft.core.tools.ToolResult;
import net.mindcraft.core.world.SelfState;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code remember_here} — save the companion's current location under a name. */
public final class RememberPlaceTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "remember_here",
            "Save the companion's current location under a name (e.g. \"home\").",
            List.of(new ParamSpec("name", ParamType.STRING,
                    "What to call this place.", true, null, null, null, null)),
            false, false, Duration.ofSeconds(10), SecurityClass.STANDARD);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        if (context.places() == null) {
            return ToolResult.failure(DEF.name(), "Place memory is unavailable.");
        }
        String name = String.valueOf(call.arguments().get("name"));
        SelfState s = context.world().selfState();
        try {
            context.places().remember(name, s.x(), s.y(), s.z());
        } catch (IOException e) {
            throw new ToolException("could not save place '" + name + "'", e);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("place", name);
        Map<String, Object> pos = new LinkedHashMap<>();
        pos.put("x", round2(s.x()));
        pos.put("y", round2(s.y()));
        pos.put("z", round2(s.z()));
        data.put("position", pos);
        return ToolResult.success(DEF.name(), "Remembered this spot as '" + name + "'.", data);
    }

    private static String round2(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }
}
