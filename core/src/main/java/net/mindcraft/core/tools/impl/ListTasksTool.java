package net.mindcraft.core.tools.impl;

import net.mindcraft.core.agent.AgentContext;
import net.mindcraft.core.tasks.AgentTask;
import net.mindcraft.core.tools.Tool;
import net.mindcraft.core.tools.ToolCall;
import net.mindcraft.core.tools.ToolDefinition;
import net.mindcraft.core.tools.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code list_tasks} — current long-running tasks and their states. */
public final class ListTasksTool implements Tool {

    private static final ToolDefinition DEF = ToolDefinition.query(
            "list_tasks",
            "List the companion's long-running tasks and their states.");

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        if (context.tasks() == null) {
            return ToolResult.failure(DEF.name(), "Task manager is unavailable.");
        }
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (AgentTask t : context.tasks().active()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("task_id", t.id());
            m.put("status", t.status().jsonName());
            m.put("description", t.description());
            tasks.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tasks", tasks);
        return ToolResult.success(DEF.name(),
                tasks.isEmpty() ? "No active tasks." : tasks.size() + " active task(s).", data);
    }
}
