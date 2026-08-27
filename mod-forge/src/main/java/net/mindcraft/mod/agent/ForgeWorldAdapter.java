package net.mindcraft.mod.agent;

import net.mindcraft.core.world.ActionResult;
import net.mindcraft.core.world.AgentWorld;
import net.mindcraft.core.world.BlockInfo;
import net.mindcraft.core.world.CompanionInventory;
import net.mindcraft.core.world.EntityInfo;
import net.mindcraft.core.world.InventoryView;
import net.mindcraft.core.world.ItemCount;
import net.mindcraft.core.world.PlayerState;
import net.mindcraft.core.world.SelfState;
import net.mindcraft.mod.companion.CompanionEntity;
import net.mindcraft.mod.companion.CompanionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forge implementation of {@link AgentWorld}: routes every call through the
 * integrated-server gate, keeps one companion body per dimension, and reads
 * compact world state (position, biome, time, weather, nearby entities).
 */
public final class ForgeWorldAdapter implements AgentWorld {

    private static final Duration SERVER_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_ENTITIES = 32;

    private final Minecraft mc;
    private final ServerGate gate;
    private final Map<String, CompanionInventory> inventories = new ConcurrentHashMap<>();

    public ForgeWorldAdapter(Minecraft mc) {
        this.mc = mc;
        this.gate = new ServerGate(mc);
    }

    /** Stable world id (level folder name) for memory/inventory keys. */
    public String worldId() {
        try {
            return gate.onServer(SERVER_TIMEOUT, () -> gate.server().getWorldData().getLevelName());
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    @Override
    public SelfState selfState() {
        try {
            return gate.onServer(SERVER_TIMEOUT, () -> {
                ServerPlayer sp = localPlayer();
                if (sp == null) {
                    return new SelfState(0, 0, 0, "unloaded", "unknown", "unknown", "clear",
                            20, 20, "survival", null);
                }
                CompanionEntity c = CompanionManager.ensure(gate.server(), sp);
                ServerLevel level = sp.serverLevel();
                return new SelfState(
                        c.getX(), c.getY(), c.getZ(),
                        level.dimension().location().toString(),
                        biomeName(level, c.blockPosition()),
                        timeOfDay(level.getDayTime()),
                        weather(level),
                        c.getHealth(),
                        20.0D,
                        sp.gameMode.getGameModeForPlayer().getName(),
                        c.getFollowTargetName());
            });
        } catch (RuntimeException e) {
            return new SelfState(0, 0, 0, "unavailable", "unknown", "unknown", "clear",
                    20, 20, "survival", null);
        }
    }

    @Override
    public List<EntityInfo> nearbyEntities(double radius) {
        try {
            return gate.onServer(SERVER_TIMEOUT, () -> {
                ServerPlayer sp = localPlayer();
                if (sp == null) return List.of();
                CompanionEntity c = CompanionManager.ensure(gate.server(), sp);
                ServerLevel level = sp.serverLevel();
                Vec3 pos = c.position();
                List<EntityInfo> out = new ArrayList<>();
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                        AABB.ofSize(pos, radius * 2, radius * 2, radius * 2),
                        e2 -> e2 != c && e2.isAlive())) {
                    if (out.size() >= MAX_ENTITIES) break;
                    double dist = e.distanceTo(c);
                    if (dist <= radius) {
                        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
                        String type = key != null ? key.getPath() : e.getType().getDescriptionId();
                        out.add(new EntityInfo(type, dist, e.getX(), e.getY(), e.getZ(),
                                e instanceof Monster, direction(c, e)));
                    }
                }
                out.sort(Comparator.comparingDouble(EntityInfo::distance));
                return List.copyOf(out);
            });
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    @Override
    public List<EntityInfo> nearbyEntitiesNear(double x, double y, double z, double radius) {
        try {
            return gate.onServer(SERVER_TIMEOUT, () -> {
                ServerPlayer sp = localPlayer();
                if (sp == null) return List.of();
                ServerLevel level = sp.serverLevel();
                Vec3 center = new Vec3(x, y, z);
                List<EntityInfo> out = new ArrayList<>();
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                        AABB.ofSize(center, radius * 2, radius * 2, radius * 2),
                        e2 -> e2 != sp && e2.isAlive())) {
                    if (out.size() >= MAX_ENTITIES) break;
                    double dist = Math.sqrt(e.distanceToSqr(center));
                    if (dist <= radius) {
                        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
                        String type = key != null ? key.getPath() : e.getType().getDescriptionId();
                        out.add(new EntityInfo(type, dist, e.getX(), e.getY(), e.getZ(),
                                e instanceof Monster, "n/a"));
                    }
                }
                out.sort(Comparator.comparingDouble(EntityInfo::distance));
                return List.copyOf(out);
            });
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    @Override
    public Optional<BlockInfo> findNearbyBlock(String blockId, double radius) {
        if (blockId == null || blockId.isBlank()) {
            return Optional.empty();
        }
        try {
            return gate.onServer(SERVER_TIMEOUT, () -> {
                ServerPlayer sp = localPlayer();
                if (sp == null) return Optional.empty();
                CompanionEntity c = CompanionManager.ensure(gate.server(), sp);
                ServerLevel level = sp.serverLevel();
                BlockPos center = c.blockPosition();
                int r = (int) Math.ceil(Math.min(radius, 64.0D));
                BlockInfo best = null;
                double bestDistSq = Double.MAX_VALUE;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        for (int dy = -r; dy <= r; dy++) {
                            Block block = level.getBlockState(center.offset(dx, dy, dz)).getBlock();
                            ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
                            if (key == null) continue;
                            if (key.getPath().equalsIgnoreCase(blockId)
                                    || key.toString().equalsIgnoreCase(blockId)) {
                                double distSq = dx * dx + dy * dy + dz * dz;
                                if (distSq < bestDistSq) {
                                    bestDistSq = distSq;
                                    best = new BlockInfo(key.getPath(), center.getX() + dx,
                                            center.getY() + dy, center.getZ() + dz, Math.sqrt(distSq));
                                }
                            }
                        }
                    }
                }
                return Optional.ofNullable(best);
            });
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<String> visibleBlockTypes(double radius) {
        try {
            return gate.onServer(SERVER_TIMEOUT, () -> {
                ServerPlayer sp = localPlayer();
                if (sp == null) return List.of();
                CompanionEntity c = CompanionManager.ensure(gate.server(), sp);
                ServerLevel level = sp.serverLevel();
                BlockPos center = c.blockPosition();
                int r = (int) Math.ceil(Math.min(radius, 32.0D));
                java.util.LinkedHashSet<String> types = new java.util.LinkedHashSet<>();
                outer:
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        for (int dy = -r; dy <= r; dy++) {
                            Block block = level.getBlockState(center.offset(dx, dy, dz)).getBlock();
                            if (block == Blocks.AIR) continue;
                            ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
                            if (key != null) {
                                types.add(key.getPath());
                                if (types.size() >= 12) break outer;
                            }
                        }
                    }
                }
                return List.copyOf(types);
            });
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    @Override
    public boolean isNear(double x, double y, double z, double closeness) {
        try {
            return gate.onServer(SERVER_TIMEOUT, () -> {
                ServerPlayer sp = localPlayer();
                if (sp == null) return false;
                CompanionEntity c = CompanionManager.ensure(gate.server(), sp);
                return c.distanceToSqr(x, y, z) <= closeness * closeness;
            });
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public ActionResult breakBlockAt(double x, double y, double z) {
        return onServerAction(() -> {
            ServerPlayer sp = localPlayer();
            if (sp == null) return ActionResult.failed("No local player.");
            ServerLevel level = sp.serverLevel();
            BlockPos pos = BlockPos.containing(x, y, z);
            Block block = level.getBlockState(pos).getBlock();
            if (block == Blocks.AIR) {
                return ActionResult.blocked("No block at " + pos.getX() + ", "
                        + pos.getY() + ", " + pos.getZ() + ".");
            }
            level.destroyBlock(pos, false);
            ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
            String blockId = key == null ? "unknown" : key.getPath();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("block", blockId);
            data.put("x", pos.getX());
            data.put("y", pos.getY());
            data.put("z", pos.getZ());
            return ActionResult.success("Broke " + blockId + ".", data);
        });
    }

    @Override
    public ActionResult addItem(String item, int count) {
        if (item == null || item.isBlank() || count <= 0) {
            return ActionResult.failed("Invalid item/count for add_item.");
        }
        CompanionInventory inv = inventoryForWorld();
        if (inv == null) return ActionResult.failed("Inventory is unavailable.");
        try {
            inv.add(item, count);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("item", item);
            data.put("count", count);
            data.put("total", inv.count(item));
            return ActionResult.success("Added " + count + " " + item + ".", data);
        } catch (IOException e) {
            return ActionResult.failed("Could not update inventory: " + e.getMessage());
        }
    }

    @Override
    public ActionResult removeItem(String item, int count) {
        CompanionInventory inv = inventoryForWorld();
        if (inv == null) return ActionResult.failed("Inventory is unavailable.");
        try {
            int have = inv.count(item);
            if (have < count) {
                return ActionResult.blocked("Not carrying enough " + item
                        + " (have " + have + ", need " + count + ").");
            }
            int removed = inv.remove(item, count);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("item", item);
            data.put("removed", removed);
            data.put("remaining", inv.count(item));
            return ActionResult.success("Removed " + removed + " " + item + ".", data);
        } catch (IOException e) {
            return ActionResult.failed("Could not update inventory: " + e.getMessage());
        }
    }

    @Override
    public ActionResult giveItemToPlayer(String playerName, String item, int count) {
        return onServerAction(() -> {
            ServerPlayer target = gate.server().getPlayerList().getPlayerByName(playerName);
            if (target == null) {
                return ActionResult.blocked("Player '" + playerName + "' is not online right now.");
            }
            Item resolved = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(item));
            if (resolved == null || resolved == net.minecraft.world.item.Items.AIR) {
                return ActionResult.failed("Unknown item '" + item + "'.");
            }
            CompanionInventory inv = inventoryForWorld();
            if (inv == null) return ActionResult.failed("Inventory is unavailable.");
            try {
                if (inv.count(item) < count) {
                    return ActionResult.blocked("Not carrying enough " + item + " to give.");
                }
                ItemStack stack = new ItemStack(resolved, count);
                ServerLevel level = target.serverLevel();
                ItemEntity entity = new ItemEntity(level, target.getX(), target.getY() + 1.0D,
                        target.getZ(), stack);
                level.addFreshEntity(entity);
                inv.remove(item, count);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("item", item);
                data.put("count", count);
                data.put("player", playerName);
                return ActionResult.success("Gave " + count + " " + item + " to " + playerName + ".", data);
            } catch (IOException e) {
                return ActionResult.failed("Could not update inventory: " + e.getMessage());
            }
        });
    }

    @Override
    public ActionResult dropItem(String item, int count) {
        return onServerAction(() -> {
            Item resolved = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(item));
            if (resolved == null || resolved == net.minecraft.world.item.Items.AIR) {
                return ActionResult.failed("Unknown item '" + item + "'.");
            }
            CompanionInventory inv = inventoryForWorld();
            if (inv == null) return ActionResult.failed("Inventory is unavailable.");
            try {
                if (inv.count(item) < count) {
                    return ActionResult.blocked("Not carrying enough " + item + " to drop.");
                }
                CompanionEntity c = CompanionManager.ensure(gate.server(), localPlayer());
                ServerLevel level = (ServerLevel) c.level();
                ItemStack stack = new ItemStack(resolved, count);
                level.addFreshEntity(new ItemEntity(level, c.getX(), c.getY() + 0.5D, c.getZ(), stack));
                inv.remove(item, count);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("item", item);
                data.put("count", count);
                return ActionResult.success("Dropped " + count + " " + item + ".", data);
            } catch (IOException e) {
                return ActionResult.failed("Could not update inventory: " + e.getMessage());
            }
        });
    }

    @Override
    public ActionResult equipItem(String item) {
        CompanionInventory inv = inventoryForWorld();
        if (inv == null) return ActionResult.failed("Inventory is unavailable.");
        try {
            if (item == null || item.isBlank()) {
                inv.equip(null);
                return ActionResult.success("Unequipped everything.");
            }
            if (inv.count(item) < 1) {
                return ActionResult.blocked("Not carrying " + item + " to equip.");
            }
            inv.equip(item);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("equipped", item);
            return ActionResult.success("Equipped " + item + ".", data);
        } catch (IOException e) {
            return ActionResult.failed("Could not update inventory: " + e.getMessage());
        }
    }

    @Override
    public ActionResult moveAway(double distance) {
        return onServerAction(() -> {
            CompanionEntity c = CompanionManager.ensure(gate.server(), localPlayer());
            double yaw = c.getRandom().nextDouble() * Math.PI * 2.0D;
            double dx = Math.sin(yaw);
            double dz = Math.cos(yaw);
            BlockPos target = new BlockPos(
                    (int) Math.floor(c.getX() + dx * distance),
                    (int) Math.floor(c.getY()),
                    (int) Math.floor(c.getZ() + dz * distance));
            c.setMoveTarget(target, 1.0D);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("distance", distance);
            return ActionResult.success("Moving away " + distance + " blocks.", data);
        });
    }

    @Override
    public ActionResult lookAtPlayer(String playerName) {
        return onServerAction(() -> {
            ServerPlayer target = gate.server().getPlayerList().getPlayerByName(playerName);
            if (target == null) {
                return ActionResult.blocked("Player '" + playerName + "' is not online right now.");
            }
            CompanionEntity c = CompanionManager.ensure(gate.server(), localPlayer());
            c.getLookControl().setLookAt(target, 90.0F, 90.0F);
            return ActionResult.success("Looking at " + playerName + ".");
        });
    }

    @Override
    public ActionResult fleeFromEntity(String entityType, double distance) {
        return onServerAction(() -> {
            CompanionEntity c = CompanionManager.ensure(gate.server(), localPlayer());
            ServerLevel level = (ServerLevel) c.level();
            LivingEntity threat = nearestEntityOfType(level, c.blockPosition(), entityType, 32.0D);
            if (threat == null) {
                return ActionResult.blocked("No " + entityType + " nearby to flee from.");
            }
            double dx = c.getX() - threat.getX();
            double dz = c.getZ() - threat.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.01D) {
                double yaw = c.getRandom().nextDouble() * Math.PI * 2.0D;
                dx = Math.sin(yaw);
                dz = Math.cos(yaw);
                len = 1.0D;
            }
            BlockPos target = new BlockPos(
                    (int) Math.floor(c.getX() + dx / len * distance),
                    (int) Math.floor(c.getY()),
                    (int) Math.floor(c.getZ() + dz / len * distance));
            c.setMoveTarget(target, 1.0D);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", entityType);
            data.put("distance", distance);
            return ActionResult.success("Fleeing from " + entityType + ".", data);
        });
    }

    @Override
    public ActionResult attackEntity(String entityType) {
        return onServerAction(() -> {
            CompanionEntity c = CompanionManager.ensure(gate.server(), localPlayer());
            ServerLevel level = (ServerLevel) c.level();
            LivingEntity target = nearestEntityOfType(level, c.blockPosition(), entityType, 8.0D);
            if (target == null) {
                return ActionResult.blocked("No " + entityType + " within 8 blocks.");
            }
            boolean hit = c.doHurtTarget(target);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", entityType);
            return hit ? ActionResult.success("Attacked " + entityType + ".", data)
                    : ActionResult.failed("The attack on " + entityType + " missed or was blocked.");
        });
    }

    @Override
    public ActionResult defendPlayer(String playerName, double distance) {
        return onServerAction(() -> {
            ServerPlayer target = gate.server().getPlayerList().getPlayerByName(playerName);
            if (target == null) {
                return ActionResult.blocked("Player '" + playerName + "' is not online right now.");
            }
            ServerLevel level = target.serverLevel();
            LivingEntity hostile = nearestHostileNear(level, target.blockPosition(), distance);
            if (hostile == null) {
                return ActionResult.blocked("No hostiles near " + playerName + ".");
            }
            CompanionEntity c = CompanionManager.ensure(gate.server(), localPlayer());
            boolean hit = c.doHurtTarget(hostile);
            ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(hostile.getType());
            String type = key == null ? "hostile" : key.getPath();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", type);
            return hit ? ActionResult.success("Defended " + playerName + " from a " + type + ".", data)
                    : ActionResult.failed("Could not hit the " + type + ".");
        });
    }

    @Override
    public InventoryView inventory() {
        CompanionInventory inv = inventoryForWorld();
        List<ItemCount> items = inv == null ? List.of() : inv.snapshot();
        List<String> equipped = inv == null ? List.of() : inv.equipped();
        return new InventoryView(items, equipped);
    }

    @Override
    public PlayerState playerState(String playerName) {
        try {
            return gate.onServer(SERVER_TIMEOUT, () -> {
                ServerPlayer p = gate.server().getPlayerList().getPlayerByName(playerName);
                if (p == null) return new PlayerState(playerName, 0, 0, 0, -1, 0, false);
                CompanionEntity c = CompanionManager.ensure(gate.server(), localPlayer());
                return new PlayerState(playerName, p.getX(), p.getY(), p.getZ(),
                        c.distanceTo(p), p.getHealth(), true);
            });
        } catch (RuntimeException e) {
            return new PlayerState(playerName, 0, 0, 0, -1, 0, false);
        }
    }

    @Override
    public List<String> nearbyPlayers(double radius) {
        try {
            return gate.onServer(SERVER_TIMEOUT, () -> {
                ServerPlayer sp = localPlayer();
                if (sp == null) return List.of();
                CompanionEntity c = CompanionManager.ensure(gate.server(), sp);
                List<String> names = new ArrayList<>();
                for (ServerPlayer p : gate.server().getPlayerList().getPlayers()) {
                    if (p != sp && c.distanceToSqr(p) <= radius * radius) {
                        names.add(p.getName().getString());
                    }
                }
                return List.copyOf(names);
            });
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    @Override
    public ActionResult goTo(double x, double y, double z, double closeness) {
        return onServerAction(() -> {
            CompanionEntity c = CompanionManager.ensure(gate.server(), localPlayer());
            c.setMoveTarget(BlockPos.containing(x, y, z), closeness);
            Map<String, Object> data = new LinkedHashMap<>();
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("x", x);
            target.put("y", y);
            target.put("z", z);
            data.put("target", target);
            data.put("closeness", closeness);
            return ActionResult.success("Started walking to the target.", data);
        });
    }

    @Override
    public ActionResult goToPlayer(String playerName, double closeness) {
        return onServerAction(() -> {
            ServerPlayer target = gate.server().getPlayerList().getPlayerByName(playerName);
            if (target == null) {
                return ActionResult.blocked("Player '" + playerName + "' is not online right now.");
            }
            CompanionEntity c = CompanionManager.ensure(gate.server(), localPlayer());
            c.setMoveTarget(target.blockPosition(), closeness);
            return ActionResult.success("On my way to " + playerName + ".");
        });
    }

    @Override
    public ActionResult followPlayer(String playerName, double distance) {
        return onServerAction(() -> {
            ServerPlayer target = gate.server().getPlayerList().getPlayerByName(playerName);
            if (target == null) {
                return ActionResult.blocked("Player '" + playerName + "' is not online right now.");
            }
            CompanionEntity c = CompanionManager.ensure(gate.server(), localPlayer());
            c.setFollow(playerName, distance);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("following", playerName);
            data.put("distance", distance);
            return ActionResult.success("Now following " + playerName + ".", data);
        });
    }

    @Override
    public ActionResult stopFollowing() {
        return onServerAction(() -> {
            CompanionEntity c = CompanionManager.ensure(gate.server(), localPlayer());
            c.clearFollow();
            return ActionResult.success("Stopped following.");
        });
    }

    @Override
    public boolean isFollowing() {
        return followingPlayer().isPresent();
    }

    @Override
    public Optional<String> followingPlayer() {
        try {
            return gate.onServer(SERVER_TIMEOUT, () -> {
                ServerPlayer sp = localPlayer();
                if (sp == null) return Optional.empty();
                CompanionEntity c = CompanionManager.ensure(gate.server(), sp);
                String name = c.getFollowTargetName();
                return name == null ? Optional.empty() : Optional.of(name);
            });
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // --- helpers ------------------------------------------------------------

    private ActionResult onServerAction(java.util.function.Supplier<ActionResult> task) {
        try {
            return gate.onServer(SERVER_TIMEOUT, task);
        } catch (RuntimeException e) {
            return ActionResult.failed("Companion action failed: " + e.getMessage());
        }
    }

    private ServerPlayer localPlayer() {
        return gate.server().getPlayerList().getPlayerByName(mc.getUser().getName());
    }

    private CompanionInventory inventoryForWorld() {
        String worldId = worldId();
        return inventories.computeIfAbsent(worldId, w -> {
            Path memoryDir = Path.of(".").toAbsolutePath().resolve("mindcraft/memory");
            try {
                return CompanionInventory.forWorld(memoryDir, w);
            } catch (IOException e) {
                return null;
            }
        });
    }

    private static String biomeName(ServerLevel level, BlockPos pos) {
        try {
            ResourceLocation key = ForgeRegistries.BIOMES.getKey(level.getBiome(pos).value());
            return key == null ? "unknown" : key.toString();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static String timeOfDay(long dayTime) {
        long t = Math.floorMod(dayTime, 24000L);
        if (t < 6000) return "morning";
        if (t < 12000) return "afternoon";
        if (t < 18000) return "evening";
        return "night";
    }

    private static String weather(ServerLevel level) {
        if (level.isThundering()) return "thunderstorm";
        if (level.isRaining()) return "rain";
        return "clear";
    }

    /** 8-way direction of {@code e} relative to the companion's facing. */
    private static String direction(CompanionEntity c, LivingEntity e) {
        double dx = e.getX() - c.getX();
        double dz = e.getZ() - c.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) return "here";
        dx /= len;
        dz /= len;
        double yaw = Math.toRadians(c.getYRot());
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double dot = fx * dx + fz * dz;
        double cross = fx * dz - fz * dx;
        double angle = Math.toDegrees(Math.atan2(cross, dot));
        if (Math.abs(angle) < 22.5) return "front";
        if (angle >= 157.5 || angle <= -157.5) return "behind";
        if (angle > 0) {
            return angle < 67.5 ? "front-left" : angle < 112.5 ? "left" : "behind-left";
        }
        double a = -angle;
        return a < 67.5 ? "front-right" : a < 112.5 ? "right" : "behind-right";
    }

    private LivingEntity nearestEntityOfType(ServerLevel level, BlockPos center, String type, double radius) {
        List<LivingEntity> found = level.getEntitiesOfClass(LivingEntity.class,
                AABB.ofSize(center.getCenter(), radius * 2, radius * 2, radius * 2),
                e -> e.isAlive() && matchesType(e, type));
        return found.stream().min(Comparator.comparingDouble(
                e -> e.distanceToSqr(center.getX(), center.getY(), center.getZ()))).orElse(null);
    }

    private LivingEntity nearestHostileNear(ServerLevel level, BlockPos center, double radius) {
        List<LivingEntity> found = level.getEntitiesOfClass(LivingEntity.class,
                AABB.ofSize(center.getCenter(), radius * 2, radius * 2, radius * 2),
                e -> e.isAlive() && e instanceof Monster);
        return found.stream().min(Comparator.comparingDouble(
                e -> e.distanceToSqr(center.getX(), center.getY(), center.getZ()))).orElse(null);
    }

    private static boolean matchesType(LivingEntity e, String type) {
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
        return key != null && (key.getPath().equalsIgnoreCase(type) || key.toString().equalsIgnoreCase(type));
    }
}
