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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@code view_container} — inspect a virtual container's contents. */
public final class ViewContainerTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "view_container",
            "Look inside a container by name and list what it holds.",
            List.of(new ParamSpec("name", ParamType.STRING,
                    "Container name.", true, null, null, null, null)),
            true, false, Duration.ZERO, SecurityClass.QUERY);

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
        Optional<Map<String, Integer>> contents = context.containers().view(name);
        if (contents.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("known_containers", context.containers().names());
            return ToolResult.blocked(DEF.name(), "No container named '" + name + "' is open.", data);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("container", name);
        List<Map<String, Object>> items = OpenContainerTool.renderItems(contents.get());
        data.put("items", items);
        return ToolResult.success(DEF.name(), "'" + name + "' holds "
                + items.size() + " item type(s).", data);
    }
}
