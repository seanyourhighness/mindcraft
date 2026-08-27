package net.mindcraft.core.events;

import java.util.HashMap;
import java.util.Map;

/**
 * Relevance gate in front of the LLM. Scores events by priority, proximity
 * and novelty, then suppresses repeats and same-type spam within a cooldown.
 * Most events never reach the model — that is the point: a companion that
 * reacts to everything is annoying; one that notices the right things feels
 * intelligent.
 */
public final class SalienceGate {

    public static final double NOTIFY_THRESHOLD = 0.55;
    public static final long DEFAULT_COOLDOWN_MS = 10_000L;
    public static final double PROXIMITY_RANGE = 32.0D;
    private static final double REPETITION_PENALTY = 0.15D;
    private static final double MAX_REPETITION_PENALTY = 0.45D;

    private final long cooldownMs;
    private final Map<String, Long> lastNotifiedAt = new HashMap<>();
    private final Map<String, Integer> recentCounts = new HashMap<>();

    public SalienceGate() {
        this(DEFAULT_COOLDOWN_MS);
    }

    public SalienceGate(long cooldownMs) {
        this.cooldownMs = Math.max(0, cooldownMs);
    }

    /** Assess an event and update suppression state if it should notify. */
    public synchronized SalienceDecision assess(SemanticEvent e) {
        return assess(e, false);
    }

    /**
     * Assess an event; {@code curated} signals (already filtered by a watch
     * set) bypass the salience threshold but still respect cooldown and
     * repetition suppression, so a watched diamond find is noticed without
     * spamming.
     */
    public synchronized SalienceDecision assess(SemanticEvent e, boolean curated) {
        String type = e.type();
        int repeats = recentCounts.getOrDefault(type, 0);
        double score = score(e, repeats);

        long now = System.currentTimeMillis();
        Long last = lastNotifiedAt.get(type);
        boolean inCooldown = last != null && now - last < cooldownMs;
        boolean suppressedByCooldown = inCooldown && !e.priority().alwaysNotify();
        boolean belowThreshold = score < NOTIFY_THRESHOLD && !e.priority().alwaysNotify() && !curated;

        recentCounts.merge(type, 1, Integer::sum);
        if (!suppressedByCooldown && !belowThreshold) {
            lastNotifiedAt.put(type, now);
            recentCounts.put(type, 0);
            return SalienceDecision.pass(e, score);
        }
        if (suppressedByCooldown) {
            return SalienceDecision.suppress(e, score, "same-type event on cooldown");
        }
        return SalienceDecision.suppress(e, score, "below salience threshold");
    }

    private double score(SemanticEvent e, int repeats) {
        double score = e.priority().weight();
        if (e.proximity() != null) {
            double near = Math.max(0.0D, 1.0D - e.proximity() / PROXIMITY_RANGE);
            score += 0.20D * near;
        }
        double penalty = Math.min(MAX_REPETITION_PENALTY, repeats * REPETITION_PENALTY);
        score -= penalty;
        return Math.max(0.0D, Math.min(1.0D, score));
    }
}
