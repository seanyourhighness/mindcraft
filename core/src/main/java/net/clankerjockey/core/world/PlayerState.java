package net.clankerjockey.core.world;

/** Compact snapshot of a player observed by the companion. */
public record PlayerState(
        String name,
        double x,
        double y,
        double z,
        double distance,
        double health,
        boolean online) {

    public PlayerState {
        name = name == null ? "unknown" : name;
    }
}
