package net.clankerjockey.core.tools;

/**
 * JSON-compatible parameter types used by tool schemas. The validator maps
 * values parsed by {@code MiniJson} (Long/Double/String/Boolean) onto these.
 */
public enum ParamType {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN
}
