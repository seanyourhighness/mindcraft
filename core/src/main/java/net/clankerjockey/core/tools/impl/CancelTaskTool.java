package net.clankerjockey.core.tools.impl;

import net.clankerjockey.core.agent.AgentContext;
import net.clankerjockey.core.tasks.AgentTask;
import net.clankerjockey.core.tasks.TaskStatus;
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

/** {@code cancel_task} — stop a long-running task by id. */
public final class CancelTaskTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "cancel_task",
            "Cancel a long-running task by its task id.",
            List.of(new ParamSpec("task_id", ParamType.STRING,
                    "The task id returned by start_task.", true, null, null, null, null)),
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
        String taskId = String.valueOf(call.arguments().get("task_id"));
        Optional<AgentTask> task = context.tasks().get(taskId);
        if (task.isEmpty()) {
            return ToolResult.failure(DEF.name(), "No task with id '" + taskId + "'.");
        }
        if (task.get().isTerminal()) {
            return ToolResult.failure(DEF.name(), taskId + " is already "
                    + task.get().status().jsonName() + " and cannot be cancelled.");
        }
        context.tasks().cancel(taskId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task_id", taskId);
        data.put("status", TaskStatus.CANCELLED.jsonName());
        return ToolResult.success(DEF.name(), "Cancelled " + taskId + ".", data);
    }
}
