package net.mindcraft.core.tools.impl;

import net.mindcraft.core.agent.AgentContext;
import net.mindcraft.core.tasks.AgentTask;
import net.mindcraft.core.tools.ParamSpec;
import net.mindcraft.core.tools.ParamType;
import net.mindcraft.core.tools.SecurityClass;
import net.mindcraft.core.tools.Tool;
import net.mindcraft.core.tools.ToolCall;
import net.mindcraft.core.tools.ToolDefinition;
import net.mindcraft.core.tools.ToolResult;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code start_task} — begin a long-running companion task and return its id
 * immediately, so the companion can keep talking while the task progresses.
 */
public final class StartTaskTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "start_task",
            "Start a long-running task (e.g. collecting items, building) and get its task id.",
            List.of(new ParamSpec("description", ParamType.STRING,
                    "What the task should accomplish.", true, null, null, null, null)),
            false, true, Duration.ofSeconds(10), SecurityClass.STANDARD);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        if (context.tasks() == null) {
            return ToolResult.failure(DEF.name(), "Task manager is unavailable.");
        }
        String description = String.valueOf(call.arguments().get("description"));
        AgentTask task = context.tasks().start(description);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task_id", task.id());
        data.put("status", task.status().jsonName());
        return ToolResult.success(DEF.name(),
                "Task '" + description + "' started as " + task.id() + ".", data);
    }
}
