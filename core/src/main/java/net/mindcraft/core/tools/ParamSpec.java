package net.mindcraft.core.tools;

import java.util.List;
import java.util.Objects;

/**
 * Typed parameter of a tool schema.
 *
 * @param name          parameter name as it appears in the JSON arguments
 * @param type          JSON type (STRING/INTEGER/NUMBER/BOOLEAN)
 * @param description   short description for the model
 * @param required      whether the model must supply the value
 * @param min           inclusive minimum for INTEGER/NUMBER (null = unbounded)
 * @param max           inclusive maximum for INTEGER/NUMBER (null = unbounded)
 * @param allowedValues closed set of allowed values (null = any)
 * @param defaultValue  applied by the validator when the value is missing and
 *                      {@code required} is false (null = none)
 */
public record ParamSpec(String name, ParamType type, String description,
                        boolean required, Double min, Double max,
                        List<String> allowedValues, Object defaultValue) {

    public ParamSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("param name must not be blank");
        }
        Objects.requireNonNull(type, "type");
        if (min != null && max != null && min > max) {
            throw new IllegalArgumentException("min > max for param " + name);
        }
        allowedValues = allowedValues == null ? null : List.copyOf(allowedValues);
    }

    /** Required param with no range constraints. */
    public static ParamSpec required(String name, ParamType type, String description) {
        return new ParamSpec(name, type, description, true, null, null, null, null);
    }

    /** Required numeric param with an inclusive range. */
    public static ParamSpec requiredNumber(String name, String description, double min, double max) {
        return new ParamSpec(name, ParamType.NUMBER, description, true, min, max, null, null);
    }

    /** Optional param with a default value. */
    public static ParamSpec optional(String name, ParamType type, String description, Object defaultValue) {
        return new ParamSpec(name, type, description, false, null, null, null, defaultValue);
    }
}
