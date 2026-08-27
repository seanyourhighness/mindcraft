package net.mindcraft.core.tools;

/** Unchecked failure raised by a {@link Tool} implementation. */
public class ToolException extends RuntimeException {

    public ToolException(String message) {
        super(message);
    }

    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
