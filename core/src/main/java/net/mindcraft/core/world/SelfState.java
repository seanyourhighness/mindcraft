package net.mindcraft.core.world;

/** Compact snapshot of the companion's own state. */
public record SelfState(
        double x,
        double y,
        double z,
        String dimension,
        String biome,
        String timeOfDay,
        String weather,
        double health,
        double hunger,
        String gameMode,
        String followingPlayer) {

    public SelfState {
        dimension = dimension == null ? "unknown" : dimension;
        biome = biome == null ? "unknown" : biome;
        timeOfDay = timeOfDay == null ? "unknown" : timeOfDay;
        weather = weather == null ? "clear" : weather;
        gameMode = gameMode == null ? "survival" : gameMode;
    }
}
