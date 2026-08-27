package net.clankerjockey.core.tools;

import net.clankerjockey.core.engine.MiniJson;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A parsed, unvalidated tool invocation produced by the model.
 *
 * @param name      tool name as emitted by the model
 * @param arguments raw argument map as parsed from JSON (never trusted until
 *                  {@link ToolValidator} has checked it)
 */
public record ToolCall(String name, Map<String, Object> arguments) {

    public ToolCall {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool call name must not be blank");
        }
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    /**
     * Parse a tool call from a MiniJson tree shaped like
     * {@code {"tool": "...", "arguments": {...}}}. Accepts {@code "args"} as
     * a legacy alias for {@code "arguments"}.
     *
     * @throws IllegalArgumentException when the tree is not a valid tool call
     */
    public static ToolCall fromJson(Object tree) {
        if (!(tree instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("tool call must be a JSON object");
        }
        Object nameObj = map.get("tool");
        if (!(nameObj instanceof String name) || name.isBlank()) {
            throw new IllegalArgumentException("tool call missing string 'tool'");
        }
        Object argsObj = map.containsKey("arguments") ? map.get("arguments") : map.get("args");
        Map<String, Object> args = new LinkedHashMap<>();
        if (argsObj != null) {
            if (!(argsObj instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("tool call 'arguments' must be a JSON object");
            }
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                if (e.getKey() instanceof String k) {
                    args.put(k, e.getValue());
                }
            }
        }
        return new ToolCall(name, args);
    }

    /** Try to parse; returns null when the text is not a tool-call object. */
    public static ToolCall tryParse(Object tree) {
        try {
            return fromJson(tree);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Stable signature used for duplicate-call detection. */
    public String signature() {
        return name + ":" + MiniJson.stringify(arguments);
    }

    /** Compact JSON for feeding back into the context. */
    public String render() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tool", name);
        out.put("arguments", arguments);
        return MiniJson.stringify(out);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ToolCall that)) return false;
        return name.equals(that.name) && arguments.equals(that.arguments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, arguments);
    }
}
