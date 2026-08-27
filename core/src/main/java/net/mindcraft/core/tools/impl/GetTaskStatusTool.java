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
import java.util.Optional;

/** {@code get_task_status} — poll a long-running task's state. */
public final class GetTaskStatusTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "get_task_status",
            "Get the status of a long-running task by its task id.",
            List.of(new ParamSpec("task_id", ParamType.STRING,
                    "The task id returned by start_task.", true, null, null, null, null)),
            true, false, Duration.ZERO, SecurityClass.QUERY);

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        if (context.tasks() == null) {
            return ToolResult.failure(DEF.name(), "Task manager is unavailable.");
        }
        String taskId = String.valueOf(call.arguments().get("task_id"));
        Optional<AgentTask> task = context.tasks().get(taskId);
        if (task.isEmpty()) {
            return ToolResult.failure(DEF.name(), "No task with id '" + taskId + "'.");
        }
        AgentTask t = task.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task_id", t.id());
        data.put("status", t.status().jsonName());
        data.put("message", t.message());
        data.putAll(t.data());
        return ToolResult.success(DEF.name(), t.id() + " is " + t.status().jsonName() + ".", data);
    }
}
