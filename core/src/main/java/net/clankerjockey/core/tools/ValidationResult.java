package net.clankerjockey.core.tools;

import java.util.List;
import java.util.Map;

/**
 * Outcome of validating a raw {@link ToolCall} against a
 * {@link ToolDefinition}. The LLM is never trusted just because it emitted
 * valid JSON: argument names, types, ranges and allowed values are all
 * checked here independently.
 */
public record ValidationResult(boolean valid, List<String> issues,
                               Map<String, Object> normalizedArguments) {

    public ValidationResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
        normalizedArguments = normalizedArguments == null ? Map.of() : Map.copyOf(normalizedArguments);
    }

    public static ValidationResult ok(Map<String, Object> normalized) {
        return new ValidationResult(true, List.of(), normalized);
    }

    public static ValidationResult invalid(List<String> issues) {
        return new ValidationResult(false, issues, Map.of());
    }
}
