package net.clankerjockey.core.events;

/**
 * Cooldown for ambient (self-initiated) companion speech. Ensures the
 * companion warns about a creeper without narrating every game event: at most
 * one proactive turn per interval, per session.
 */
public final class ProactivePolicy {

    public static final long DEFAULT_MIN_INTERVAL_MS = 60_000L;

    private final long minIntervalMs;
    private volatile long lastTriggeredAt;

    public ProactivePolicy() {
        this(DEFAULT_MIN_INTERVAL_MS);
    }

    public ProactivePolicy(long minIntervalMs) {
        this.minIntervalMs = Math.max(0, minIntervalMs);
    }

    /** True when a proactive turn may start now; consumes the slot. */
    public synchronized boolean shouldTrigger(long now) {
        if (now - lastTriggeredAt < minIntervalMs) {
            return false;
        }
        lastTriggeredAt = now;
        return true;
    }
}
