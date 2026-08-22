package net.mindcraft.core.engine;

/** Thrown when the inference engine cannot start, serve, or generate. */
public class EngineException extends Exception {

    public EngineException(String message) {
        super(message);
    }

    public EngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
