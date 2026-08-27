package net.clankerjockey.core.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe registry of available tools. Registration happens once at
 * startup; lookups happen per tool call.
 */
public final class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public synchronized void register(Tool tool) {
        ToolDefinition def = tool.definition();
        if (def == null || def.name() == null || def.name().isBlank()) {
            throw new IllegalArgumentException("tool definition/name must not be null");
        }
        if (tools.containsKey(def.name())) {
            throw new IllegalArgumentException("duplicate tool name: " + def.name());
        }
        tools.put(def.name(), tool);
    }

    /** Register all tools; fails atomically if any name collides. */
    public synchronized void registerAll(Collection<? extends Tool> newTools) {
        for (Tool t : newTools) {
            register(t);
        }
    }

    public synchronized Tool get(String name) {
        return name == null ? null : tools.get(name);
    }

    public synchronized boolean contains(String name) {
        return name != null && tools.containsKey(name);
    }

    public synchronized List<String> names() {
        return List.copyOf(tools.keySet());
    }

    public synchronized List<ToolDefinition> definitions() {
        List<ToolDefinition> defs = new ArrayList<>(tools.size());
        for (Tool t : tools.values()) {
            defs.add(t.definition());
        }
        return List.copyOf(defs);
    }

    public synchronized int size() {
        return tools.size();
    }
}
