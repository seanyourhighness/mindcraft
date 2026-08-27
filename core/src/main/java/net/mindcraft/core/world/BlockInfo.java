package net.mindcraft.core.world;

/** Compact observation of a block: id, position and distance from the companion. */
public record BlockInfo(String block, double x, double y, double z, double distance) {

    public BlockInfo {
        block = block == null ? "unknown" : block;
    }
}
