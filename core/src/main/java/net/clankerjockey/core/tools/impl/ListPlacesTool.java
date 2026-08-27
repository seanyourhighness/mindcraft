package net.clankerjockey.core.tools.impl;

import net.clankerjockey.core.agent.AgentContext;
import net.clankerjockey.core.tools.Tool;
import net.clankerjockey.core.tools.ToolCall;
import net.clankerjockey.core.tools.ToolDefinition;
import net.clankerjockey.core.tools.ToolResult;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@code list_known_places} — names of every saved place. */
public final class ListPlacesTool implements Tool {

    private static final ToolDefinition DEF = ToolDefinition.query(
            "list_known_places",
            "List the names of all saved places.");

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        if (context.places() == null) {
            return ToolResult.failure(DEF.name(), "Place memory is unavailable.");
        }
        java.util.List<String> names = context.places().names();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("places", names);
        return ToolResult.success(DEF.name(),
                names.isEmpty() ? "No places saved yet." : "Saved places: " + String.join(", ", names), data);
    }
}
