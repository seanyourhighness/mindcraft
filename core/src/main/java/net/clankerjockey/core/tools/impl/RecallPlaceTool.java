package net.clankerjockey.core.tools.impl;

import net.clankerjockey.core.agent.AgentContext;
import net.clankerjockey.core.tools.ParamSpec;
import net.clankerjockey.core.tools.ParamType;
import net.clankerjockey.core.tools.SecurityClass;
import net.clankerjockey.core.tools.Tool;
import net.clankerjockey.core.tools.ToolCall;
import net.clankerjockey.core.tools.ToolDefinition;
import net.clankerjockey.core.tools.ToolResult;
import net.clankerjockey.core.world.PlaceMemory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@code recall_place} — look up a saved place's coordinates. */
public final class RecallPlaceTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "recall_place",
            "Look up a saved place by name and get its coordinates.",
            List.of(new ParamSpec("name", ParamType.STRING,
                    "Name of the saved place.", true, null, null, null, null)),
            true, false, Duration.ofSeconds(10), SecurityClass.QUERY);

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
        Optional<PlaceMemory.Place> place = context.places().recall(name);
        if (place.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("known_places", context.places().names());
            return ToolResult.blocked(DEF.name(),
                    "No place named '" + name + "' is saved.", data);
        }
        PlaceMemory.Place p = place.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("place", p.name());
        Map<String, Object> pos = new LinkedHashMap<>();
        pos.put("x", round2(p.x()));
        pos.put("y", round2(p.y()));
        pos.put("z", round2(p.z()));
        data.put("position", pos);
        return ToolResult.success(DEF.name(), "'" + p.name() + "' is at "
                + round2(p.x()) + ", " + round2(p.y()) + ", " + round2(p.z()) + ".", data);
    }

    private static String round2(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }
}
