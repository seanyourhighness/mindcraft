package net.clankerjockey.mod.companion;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side companion body management: ensures exactly one companion exists
 * per dimension, spawning it next to the local player when needed.
 */
public final class CompanionManager {

    private static final Map<ResourceKey<Level>, CompanionEntity> COMPANIONS = new ConcurrentHashMap<>();

    private CompanionManager() {
    }

    /** Must run on the server thread. Spawns the companion if absent. */
    public static CompanionEntity ensure(MinecraftServer server, ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        CompanionEntity existing = COMPANIONS.get(level.dimension());
        if (existing != null && existing.isAlive() && existing.level() == level) {
            return existing;
        }
        CompanionEntity companion = CompanionEntity.create(level);
        Vec3 p = player.position();
        companion.moveTo(p.x, p.y, p.z, player.getYRot(), 0.0F);
        level.addFreshEntity(companion);
        COMPANIONS.put(level.dimension(), companion);
        return companion;
    }
}
