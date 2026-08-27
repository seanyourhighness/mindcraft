package net.clankerjockey.mod.companion;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side companion body management (Fabric): ensures exactly one
 * companion exists per dimension, spawning it next to the local player.
 */
public final class CompanionManager {

    private static final Map<RegistryKey<World>, CompanionEntity> COMPANIONS = new ConcurrentHashMap<>();

    private CompanionManager() {
    }

    /** Must run on the server thread. Spawns the companion if absent. */
    public static CompanionEntity ensure(MinecraftServer server, ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        CompanionEntity existing = COMPANIONS.get(world.getRegistryKey());
        if (existing != null && existing.isAlive() && existing.getWorld() == world) {
            return existing;
        }
        CompanionEntity companion = CompanionEntity.create(world);
        Vec3d p = player.getPos();
        companion.refreshPositionAndAngles(p.x, p.y, p.z, player.getYaw(), 0.0F);
        world.spawnEntity(companion);
        COMPANIONS.put(world.getRegistryKey(), companion);
        return companion;
    }
}
