package net.clankerjockey.core.tasks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe registry of companion tasks. Tasks are state containers with a
 * lifecycle; the game/server thread or a worker reports progress via
 * {@link #update}, and the agent can start/query/cancel them through tools.
 */
public final class TaskManager {

    private final Map<String, AgentTask> tasks = new LinkedHashMap<>();
    private final AtomicLong counter = new AtomicLong();

    /** Create and start a task with a unique id like {@code task-42}. */
    public synchronized AgentTask start(String description) {
        String id = "task-" + counter.incrementAndGet();
        AgentTask task = new AgentTask(id, description);
        task.start();
        tasks.put(id, task);
        return task;
    }

    /** Create and start a task with structured metadata (type, block, count...). */
    public synchronized AgentTask start(String description, Map<String, Object> metadata) {
        AgentTask task = start(description);
        task.update(TaskStatus.RUNNING, "Task started.", metadata);
        return task;
    }

    public synchronized Optional<AgentTask> get(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(tasks.get(id));
    }

    /** All tasks, oldest first. */
    public synchronized List<AgentTask> all() {
        return List.copyOf(tasks.values());
    }

    /** Non-terminal tasks, oldest first. */
    public synchronized List<AgentTask> active() {
        List<AgentTask> out = new ArrayList<>();
        for (AgentTask t : tasks.values()) {
            if (!t.isTerminal()) out.add(t);
        }
        return List.copyOf(out);
    }

    /** Cancel a task by id; returns true when it was cancellable. */
    public boolean cancel(String id) {
        Optional<AgentTask> task = get(id);
        return task.isPresent() && task.get().requestCancel();
    }

    /** Update a task's state; throws for unknown ids or illegal transitions. */
    public void update(String id, TaskStatus status, String message, Map<String, Object> data) {
        AgentTask task = get(id).orElseThrow(() -> new IllegalArgumentException("unknown task id " + id));
        task.update(status, message, data);
    }
}
