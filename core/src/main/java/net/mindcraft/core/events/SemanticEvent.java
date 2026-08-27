package net.mindcraft.core.events;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A meaningful, semantic event the companion might care about. Raw game ticks
 * are converted into these by loaders or the reflex layer before salience
 * gating, so the LLM never sees per-tick noise.
 */
public record SemanticEvent(
        EventPriority priority,
        String type,
        String description,
        Double proximity,
        long createdAtMs,
        Map<String, Object> data) {

    public SemanticEvent {
        if (priority == null) priority = EventPriority.P6;
        if (type == null || type.isBlank()) type = "EVENT";
        description = description == null ? "" : description;
        createdAtMs = createdAtMs == 0 ? System.currentTimeMillis() : createdAtMs;
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static SemanticEvent of(EventPriority priority, String type, String description) {
        return new SemanticEvent(priority, type, description, null, System.currentTimeMillis(), Map.of());
    }

    public static SemanticEvent of(EventPriority priority, String type, String description,
                                   Map<String, Object> data) {
        return new SemanticEvent(priority, type, description, null, System.currentTimeMillis(), data);
    }

    /** Render as one prompt line, e.g. {@code [P0] HOSTILE_APPROACHING: creeper at 3.2m}. */
    public String renderLine() {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(priority.name()).append("] ").append(type);
        if (description != null && !description.isBlank()) {
            sb.append(": ").append(description);
        }
        return sb.toString();
    }

    /** Compact JSON for observability/logging. */
    public String toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("priority", priority.name());
        out.put("type", type);
        out.put("description", description);
        if (proximity != null) out.put("proximity", proximity);
        out.putAll(data);
        return net.mindcraft.core.engine.MiniJson.stringify(out);
    }
}
