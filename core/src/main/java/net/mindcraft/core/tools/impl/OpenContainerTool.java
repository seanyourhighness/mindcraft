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

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code open_container} — open (create if needed) a virtual container. */
public final class OpenContainerTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "open_container",
            "Open a container by name (e.g. \"chest\"); creates it if it does not exist.",
            List.of(new ParamSpec("name", ParamType.STRING,
                    "Container name.", true, null, null, null, null)),
            false, false, Duration.ofSeconds(10), SecurityClass.STANDARD);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        if (context.containers() == null) {
            return ToolResult.failure(DEF.name(), "Container storage is unavailable.");
        }
        String name = String.valueOf(call.arguments().get("name"));
        try {
            boolean created = context.containers().open(name);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("container", name);
            data.put("items", renderItems(context.containers().view(name).orElse(Map.of())));
            return ToolResult.success(DEF.name(),
                    (created ? "Opened new container '" : "Opened container '") + name + "'.", data);
        } catch (IOException e) {
            throw new ToolException("could not open container '" + name + "'", e);
        }
    }

    static List<Map<String, Object>> renderItems(Map<String, Integer> contents) {
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (Map.Entry<String, Integer> e : contents.entrySet()) {
            if (e.getValue() <= 0) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item", e.getKey());
            m.put("count", e.getValue());
            items.add(m);
        }
        return items;
    }
}
