package net.mindcraft.core.engine;

/**
 * Sampling options for a single generation call. Defaults: maxTokens 120,
 * temperature 0.7, no seed (llama-server picks one randomly).
 *
 * @param maxTokens maximum number of tokens to generate
 * @param temperature sampling temperature (0 = greedy)
 * @param seed       optional RNG seed; fixed seed makes output reproducible
 */
public record GenOptions(int maxTokens, double temperature, Long seed) {

    public GenOptions {
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be >= 1, got " + maxTokens);
        }
        if (temperature < 0) {
            throw new IllegalArgumentException("temperature must be >= 0, got " + temperature);
        }
    }

    /** Defaults: 120 max tokens, temperature 0.7, no seed. */
    public GenOptions() {
        this(120, 0.7, null);
    }

    /** 120 max tokens, given temperature, no seed. */
    public GenOptions(int maxTokens, double temperature) {
        this(maxTokens, temperature, null);
    }
}
