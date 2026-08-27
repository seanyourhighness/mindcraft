package net.mindcraft.core.events;

/**
 * Companion priority ladder from the goal document. Higher priorities always
 * reach the LLM; lower ones are subject to salience gating so the companion
 * ignores noise (grass, cobblestone pickups) and notices what matters.
 */
public enum EventPriority {
    /** Immediate survival (on fire, creeper imminent, 2 hearts). */
    P0(1.00, true),
    /** Owner/player explicit command. */
    P1(0.95, true),
    /** Protect an important player. */
    P2(0.85, true),
    /** Critical environmental event. */
    P3(0.75, false),
    /** Current assigned task. */
    P4(0.55, false),
    /** Autonomous companion behavior. */
    P5(0.40, false),
    /** Idle curiosity (grass nearby, sunset). */
    P6(0.25, false);

    private final double weight;
    private final boolean alwaysNotify;

    EventPriority(double weight, boolean alwaysNotify) {
        this.weight = weight;
        this.alwaysNotify = alwaysNotify;
    }

    public double weight() {
        return weight;
    }

    public boolean alwaysNotify() {
        return alwaysNotify;
    }
}
