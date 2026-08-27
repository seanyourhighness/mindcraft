package net.mindcraft.core.agent;

/**
 * A game observation the agent can react to. Signals are how the world
 * reaches the agent: sensors (event handlers, tick polls) mint them and the
 * {@link AgentRuntime} matches them against {@link Watch watches}.
 *
 * <p>Subjects are registry IDs (e.g. {@code minecraft:creeper},
 * {@code minecraft:crimson_forest}, {@code minecraft:diamond_pickaxe}) or
 * free text (chat messages).
 */
public final class Signal {

    public enum Kind {
        /** A mob spawned (near the player). */
        MOB,
        /** Player is standing in this biome (polled). */
        BIOME,
        /** A named structure is at/near the player's position (polled). */
        STRUCTURE,
        /** Player right-clicked (used) an item. */
        ITEM_USE,
        /** A chat message (player or received) was seen. */
        CHAT,
        /** Player broke a block (mining behavior). */
        BLOCK_BREAK
    }

    public final Kind kind;
    public final String subject;

    private Signal(Kind kind, String subject) {
        this.kind = kind;
        this.subject = subject;
    }

    public static Signal mob(String registryId) {
        return new Signal(Kind.MOB, registryId);
    }

    public static Signal biome(String registryId) {
        return new Signal(Kind.BIOME, registryId);
    }

    public static Signal structure(String registryId) {
        return new Signal(Kind.STRUCTURE, registryId);
    }

    public static Signal itemUse(String registryId) {
        return new Signal(Kind.ITEM_USE, registryId);
    }

    public static Signal chat(String message) {
        return new Signal(Kind.CHAT, message);
    }

    public static Signal blockBreak(String registryId) {
        return new Signal(Kind.BLOCK_BREAK, registryId);
    }

    @Override
    public String toString() {
        return kind + "(" + subject + ")";
    }
}
