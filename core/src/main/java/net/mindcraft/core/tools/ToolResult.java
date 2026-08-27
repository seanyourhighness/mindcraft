package net.mindcraft.core.tools;

import net.mindcraft.core.engine.MiniJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured outcome of a tool execution. Results are deliberately compact:
 * the model gets a short message plus optional structured data, never a dump
 * of Minecraft object trees.
 *
 * <p>Status semantics follow the goal document's action lifecycle:
 * SUCCESS, FAILED, BLOCKED, INTERRUPTED, CANCELLED, TIMED_OUT, DENIED.</p>
 */
public final class ToolResult {

    public enum Status {
        SUCCESS,
        FAILED,
        BLOCKED,
        INTERRUPTED,
        CANCELLED,
        TIMED_OUT,
        DENIED
    }

    /** Render a status enum into the compact JSON string fed back to the model. */
    public static String statusName(Status s) {
        return s.name().toLowerCase();
    }

    private final String toolName;
    private final Status status;
    private final String message;
    private final Map<String, Object> data;
    private final long durationMs;

    private ToolResult(String toolName, Status status, String message,
                       Map<String, Object> data, long durationMs) {
        this.toolName = toolName;
        this.status = status == null ? Status.FAILED : status;
        this.message = message == null ? "" : message;
        this.data = data == null ? Map.of() : Map.copyOf(data);
        this.durationMs = Math.max(0, durationMs);
    }

    public String toolName() {
        return toolName;
    }

    public Status status() {
        return status;
    }

    public boolean success() {
        return status == Status.SUCCESS;
    }

    public String message() {
        return message;
    }

    public Map<String, Object> data() {
        return data;
    }

    public long durationMs() {
        return durationMs;
    }

    /** Compact JSON render for the model: {@code {"status":...,"message":...,...data}}. */
    public String render() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", statusName(status));
        out.put("message", message);
        out.putAll(data);
        return MiniJson.stringify(out);
    }

    // --- factories ---------------------------------------------------------

    public static ToolResult success(String toolName, String message, Map<String, Object> data) {
        return new ToolResult(toolName, Status.SUCCESS, message, data, 0);
    }

    public static ToolResult success(String toolName, String message) {
        return success(toolName, message, Map.of());
    }

    public static ToolResult failure(String toolName, String message) {
        return new ToolResult(toolName, Status.FAILED, message, Map.of(), 0);
    }

    public static ToolResult blocked(String toolName, String message) {
        return blocked(toolName, message, Map.of());
    }

    public static ToolResult blocked(String toolName, String message, Map<String, Object> data) {
        return new ToolResult(toolName, Status.BLOCKED, message, data, 0);
    }

    public static ToolResult denied(String toolName, String message) {
        return new ToolResult(toolName, Status.DENIED, message, Map.of(), 0);
    }

    public static ToolResult interrupted(String toolName, String message) {
        return new ToolResult(toolName, Status.INTERRUPTED, message, Map.of(), 0);
    }

    public static ToolResult cancelled(String toolName, String message) {
        return new ToolResult(toolName, Status.CANCELLED, message, Map.of(), 0);
    }

    public static ToolResult timedOut(String toolName, long timeoutMs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timeout_ms", timeoutMs);
        return new ToolResult(toolName, Status.TIMED_OUT,
                "Tool exceeded its " + timeoutMs + "ms execution budget and was interrupted.", data, 0);
    }

    /** Copy with a duration attached (used by the executor). */
    public ToolResult withDuration(long durationMs) {
        return new ToolResult(toolName, status, message, data, durationMs);
    }
}
