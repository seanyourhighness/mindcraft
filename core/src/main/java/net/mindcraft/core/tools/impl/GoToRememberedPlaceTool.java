package net.mindcraft.core.tools.impl;

import net.mindcraft.core.agent.AgentContext;
import net.mindcraft.core.tools.ParamSpec;
import net.mindcraft.core.tools.ParamType;
import net.mindcraft.core.tools.SecurityClass;
import net.mindcraft.core.tools.Tool;
import net.mindcraft.core.tools.ToolCall;
import net.mindcraft.core.tools.ToolDefinition;
import net.mindcraft.core.tools.ToolResult;
import net.mindcraft.core.world.PlaceMemory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@code go_to_remembered_place} — walk to a saved place by name. */
public final class GoToRememberedPlaceTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "go_to_remembered_place",
            "Walk the companion to a saved place by name.",
            List.of(new ParamSpec("name", ParamType.STRING,
                    "Name of the saved place to go to.", true, null, null, null, null)),
            false, true, Duration.ofSeconds(60), SecurityClass.STANDARD);

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
        return WorldToolSupport.fromAction(DEF, context.world().goTo(p.x(), p.y(), p.z(), 2.0D));
    }
}
