package net.clankerjockey.core.tools;

import java.util.Map;

/**
 * A tool call that passed schema validation, with normalized arguments
 * (JSON Longs coerced where the schema demands them, defaults applied).
 */
public record ValidatedCall(String toolName, Map<String, Object> arguments) {

    public ValidatedCall {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
