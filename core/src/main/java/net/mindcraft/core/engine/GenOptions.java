package net.mindcraft.core.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sampling options for a single generation call. Defaults: maxTokens 120,
 * temperature 0.7, no seed (llama-server picks one randomly), thinking
 * enabled (no chat_template_kwargs sent).
 *
 * @param maxTokens maximum number of tokens to generate
 * @param temperature sampling temperature (0 = greedy)
 * @param seed       optional RNG seed; fixed seed makes output reproducible
 * @param extraBody  optional top-level JSON body fields merged into the
 *                   request (e.g. {@code chat_template_kwargs}); values are
 *                   emitted via {@link MiniJson#stringify(Object)}
 */
public record GenOptions(int maxTokens, double temperature, Long seed,
                         Map<String, Object> extraBody) {

    public GenOptions {
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be >= 1, got " + maxTokens);
        }
        if (temperature < 0) {
            throw new IllegalArgumentException("temperature must be >= 0, got " + temperature);
        }
        extraBody = extraBody == null ? Map.of() : Map.copyOf(extraBody);
    }

    /** Defaults: 120 max tokens, temperature 0.7, no seed, no extras. */
    public GenOptions() {
        this(120, 0.7, null, null);
    }

    /** 120 max tokens, given temperature, no seed, no extras. */
    public GenOptions(int maxTokens, double temperature) {
        this(maxTokens, temperature, null, null);
    }

    /**
     * Convenience: options with {@code chat_template_kwargs.enable_thinking =
     * false}, required for thinking-style models (LittleLamb, Qwen3) whose
     * default template otherwise emits empty output.
     */
    public static GenOptions noThink() {
        return noThink(120, 0.7);
    }

    /** noThink with explicit sampling parameters. */
    public static GenOptions noThink(int maxTokens, double temperature) {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("enable_thinking", false);
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("chat_template_kwargs", inner);
        return new GenOptions(maxTokens, temperature, null, outer);
    }
}
