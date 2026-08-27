package net.clankerjockey.core.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Independent schema validation of model-emitted tool calls: tool exists,
 * argument names, types, ranges, allowed values and required/optional
 * handling. Produces a normalized argument map for the tool implementation.
 */
public final class ToolValidator {

    public ValidationResult validate(ToolCall call, ToolDefinition def) {
        List<String> issues = new ArrayList<>();
        Map<String, Object> normalized = new LinkedHashMap<>();
        Map<String, ParamSpec> specs = new LinkedHashMap<>();
        for (ParamSpec p : def.parameters()) {
            specs.put(p.name(), p);
        }
        for (Map.Entry<String, Object> e : call.arguments().entrySet()) {
            ParamSpec spec = specs.get(e.getKey());
            if (spec == null) {
                issues.add("unknown argument '" + e.getKey() + "'");
                continue;
            }
            Object coerced = coerce(spec, e.getValue(), issues);
            if (coerced != null) {
                normalized.put(spec.name(), coerced);
            }
        }
        for (ParamSpec p : def.parameters()) {
            if (normalized.containsKey(p.name())) {
                continue;
            }
            if (p.required()) {
                issues.add("missing required argument '" + p.name() + "'");
            } else if (p.defaultValue() != null) {
                normalized.put(p.name(), p.defaultValue());
            }
        }
        if (issues.isEmpty()) {
            return ValidationResult.ok(normalized);
        }
        return ValidationResult.invalid(issues);
    }

    private Object coerce(ParamSpec spec, Object value, List<String> issues) {
        switch (spec.type()) {
            case STRING -> {
                if (!(value instanceof String s)) {
                    issues.add("argument '" + spec.name() + "' must be a string");
                    return null;
                }
                if (spec.allowedValues() != null && !spec.allowedValues().contains(s)) {
                    issues.add("argument '" + spec.name() + "' must be one of "
                            + String.join("|", spec.allowedValues()));
                    return null;
                }
                return s;
            }
            case INTEGER -> {
                long l = asLong(value);
                if (l == Long.MIN_VALUE) {
                    issues.add("argument '" + spec.name() + "' must be an integer");
                    return null;
                }
                if (!inRange(l, spec)) {
                    issues.add("argument '" + spec.name() + "' out of range " + rangeText(spec));
                    return null;
                }
                return l;
            }
            case NUMBER -> {
                double d = asDouble(value);
                if (Double.isNaN(d)) {
                    issues.add("argument '" + spec.name() + "' must be a number");
                    return null;
                }
                if (!inRange(d, spec)) {
                    issues.add("argument '" + spec.name() + "' out of range " + rangeText(spec));
                    return null;
                }
                return d;
            }
            case BOOLEAN -> {
                if (!(value instanceof Boolean b)) {
                    issues.add("argument '" + spec.name() + "' must be a boolean");
                    return null;
                }
                return b;
            }
            default -> {
                issues.add("unsupported param type for '" + spec.name() + "'");
                return null;
            }
        }
    }

    private static long asLong(Object v) {
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        return Long.MIN_VALUE;
    }

    private static double asDouble(Object v) {
        if (v instanceof Long l) return l.doubleValue();
        if (v instanceof Integer i) return i.doubleValue();
        if (v instanceof Double d) return d;
        return Double.NaN;
    }

    private static boolean inRange(double d, ParamSpec spec) {
        return (spec.min() == null || d >= spec.min()) && (spec.max() == null || d <= spec.max());
    }

    private static String rangeText(ParamSpec spec) {
        return "[" + (spec.min() == null ? "-inf" : spec.min())
                + ", " + (spec.max() == null ? "+inf" : spec.max()) + "]";
    }
}
