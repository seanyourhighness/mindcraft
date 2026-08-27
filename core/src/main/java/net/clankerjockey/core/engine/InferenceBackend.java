package net.clankerjockey.core.engine;

/**
 * Abstraction over the inference backend so downstream code (and its unit
 * tests) can swap in a stub implementation without spawning a llama-server
 * process. {@link InferenceEngine} is the production implementation.
 */
public interface InferenceBackend extends AutoCloseable {

    /**
     * Generate a completion for the given prompt.
     *
     * @throws EngineException if the backend is not running or the request fails
     */
    String generate(String prompt, GenOptions options) throws EngineException;

    /** True while the backend process is up and serving. */
    boolean isRunning();

    /** Stop the backend. Idempotent. */
    void stop();

    @Override
    default void close() {
        stop();
    }
}
