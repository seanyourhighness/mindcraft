package net.mindcraft.core.tasks;

import net.mindcraft.core.engine.MiniJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One long-running companion task (collect items, build, explore...).
 * Thread-safe: the game/server thread and the agent worker thread both touch
 * tasks, so all mutations are synchronized and terminal states are final.
 */
public final class AgentTask {

    private final String id;
    private final String description;
    private final long createdAtMs;
    private final Map<String, Object> data = new LinkedHashMap<>();

    private TaskStatus status = TaskStatus.PENDING;
    private String message = "";
    private long updatedAtMs;

    public AgentTask(String id, String description) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("task id must not be blank");
        this.id = id;
        this.description = description == null || description.isBlank() ? "unnamed task" : description;
        long now = System.currentTimeMillis();
        this.createdAtMs = now;
        this.updatedAtMs = now;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public long createdAtMs() {
        return createdAtMs;
    }

    public synchronized TaskStatus status() {
        return status;
    }

    public synchronized String message() {
        return message;
    }

    public synchronized Map<String, Object> data() {
        return Map.copyOf(data);
    }

    public synchronized boolean isTerminal() {
        return status.isTerminal();
    }

    /** Mark the task running (only from PENDING). */
    public synchronized void start() {
        transitionTo(TaskStatus.RUNNING, "Task started.", Map.of());
    }

    /** Move to a new state with a message and optional structured data. */
    public synchronized void update(TaskStatus to, String message, Map<String, Object> data) {
        if (to == null) throw new IllegalArgumentException("status must not be null");
        if (status.isTerminal()) {
            throw new IllegalStateException("task " + id + " is already terminal (" + status + ")");
        }
        if (to == TaskStatus.PENDING) {
            throw new IllegalArgumentException("cannot transition back to PENDING");
        }
        this.status = to;
        this.message = message == null ? "" : message;
        if (data != null) {
            this.data.clear();
            this.data.putAll(data);
        }
        this.updatedAtMs = System.currentTimeMillis();
    }

    /** Request cancellation; true when the task was still cancellable. */
    public synchronized boolean requestCancel() {
        if (status.isTerminal()) return false;
        update(TaskStatus.CANCELLED, "Cancelled by request.", Map.of());
        return true;
    }

    /** Compact JSON render fed back to the model. */
    public synchronized String render() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task_id", id);
        out.put("status", status.jsonName());
        out.put("message", message);
        out.putAll(data);
        return MiniJson.stringify(out);
    }

    private void transitionTo(TaskStatus to, String message, Map<String, Object> data) {
        if (status.isTerminal()) {
            throw new IllegalStateException("task " + id + " is already terminal (" + status + ")");
        }
        this.status = to;
        this.message = message;
        this.data.clear();
        if (data != null) this.data.putAll(data);
        this.updatedAtMs = System.currentTimeMillis();
    }
}
