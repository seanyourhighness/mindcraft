package net.clankerjockey.core.tools.impl;

import net.clankerjockey.core.agent.AgentContext;
import net.clankerjockey.core.tasks.AgentTask;
import net.clankerjockey.core.tasks.CollectTaskWorker;
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

/**
 * {@code start_collect_task} — begin collecting a block type until the target
 * count is reached. Returns the task id immediately; a loader-side worker
 * searches, walks to and breaks the blocks, feeding progress via
 * {@code get_task_status}.
 */
public final class StartCollectTaskTool implements Tool {

    private static final ToolDefinition DEF = new ToolDefinition(
            "start_collect_task",
            "Start a long-running task that collects a block type (e.g. \"iron_ore\") until a count is reached.",
            List.of(
                    new ParamSpec("block", ParamType.STRING,
                            "Block id to collect (e.g. \"iron_ore\", \"oak_log\").", true, null, null, null, null),
                    new ParamSpec("count", ParamType.INTEGER,
                            "How many to collect.", false, 1d, 256d, null, 16L)),
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
        String block = String.valueOf(call.arguments().get("block"));
        int count = asInt(call.arguments().get("count"), 16);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("type", CollectTaskWorker.TYPE);
        metadata.put("block", block);
        metadata.put("count", count);
        metadata.put("collected", 0);
        AgentTask task = context.tasks().start("Collect " + count + " " + block, metadata);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task_id", task.id());
        data.put("status", task.status().jsonName());
        return ToolResult.success(DEF.name(),
                "Started collecting " + count + " " + block + " (" + task.id() + ").", data);
    }

    private static int asInt(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        return def;
    }
}
