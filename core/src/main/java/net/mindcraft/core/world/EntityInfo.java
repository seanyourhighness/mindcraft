package net.mindcraft.core.world;

/**
 * Compact entity observation: type, distance, position and a coarse direction
 * from the companion's perspective (e.g. "north", "south-west").
 */
public record EntityInfo(
        String type,
        double distance,
        double x,
        double y,
        double z,
        boolean hostile,
        String direction) {

    public EntityInfo {
        type = type == null ? "unknown" : type;
        direction = direction == null ? "unknown" : direction;
    }
}
