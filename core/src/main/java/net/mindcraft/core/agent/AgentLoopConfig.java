package net.mindcraft.core.agent;

/**
 * Safety limits for the agent loop. A broken model must never spin
 * {@code find_block → find_block → ...} forever; every loop is bounded by
 * iteration counts, duplicate-call detection, failure caps and timeouts.
 */
public record AgentLoopConfig(
        int maxToolCalls,
        int maxInferenceIterations,
        int maxRepeatedIdenticalCalls,
        int maxConsecutiveFailures,
        long perCallTimeoutMs,
        long loopTimeoutMs,
        double temperature,
        int maxTokens,
        int maxToolResultChars) {

    public AgentLoopConfig {
        if (maxToolCalls < 1) maxToolCalls = 8;
        if (maxInferenceIterations < 1) maxInferenceIterations = maxToolCalls + 4;
        if (maxRepeatedIdenticalCalls < 1) maxRepeatedIdenticalCalls = 2;
        if (maxConsecutiveFailures < 1) maxConsecutiveFailures = 3;
        if (perCallTimeoutMs <= 0) perCallTimeoutMs = 30_000;
        if (loopTimeoutMs <= 0) loopTimeoutMs = 180_000;
        if (temperature < 0) temperature = 0.3;
        if (maxTokens < 1) maxTokens = 200;
        if (maxToolResultChars < 64) maxToolResultChars = 800;
    }

    /** Recommended production defaults from the goal document. */
    public static AgentLoopConfig defaults() {
        return new AgentLoopConfig(8, 12, 2, 3, 30_000, 180_000, 0.3, 200, 800);
    }
}
