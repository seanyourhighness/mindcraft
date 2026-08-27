package net.clankerjockey.mod.agent;

import net.clankerjockey.core.world.ActionResult;
import net.clankerjockey.core.world.AgentWorld;
import net.clankerjockey.core.world.BlockInfo;
import net.clankerjockey.core.world.CompanionInventory;
import net.clankerjockey.core.world.EntityInfo;
import net.clankerjockey.core.world.InventoryView;
import net.clankerjockey.core.world.ItemCount;
import net.clankerjockey.core.world.PlayerState;
import net.clankerjockey.core.world.SelfState;
import net.clankerjockey.mod.companion.CompanionEntity;
import net.clankerjockey.mod.companion.CompanionManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

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
import java.util.function.Supplier;

/**
 * Fabric implementation of {@link AgentWorld}: routes every call through the
 * integrated-server gate and keeps one companion body per dimension.
 */
public final class FabricWorldAdapter implements AgentWorld {

    private static final Duration SERVER_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_ENTITIES = 32;

    private final MinecraftClient mc;
    private final ServerGate gate;
    private final Map<String, CompanionInventory> inventories = new ConcurrentHashMap<>();

    public FabricWorldAdapter(MinecraftClient mc) {
        this.mc = mc;
        this.gate = new ServerGate(mc);
    }

    /** Stable world id (level folder name) for memory/inventory keys. */
    public String worldId() {
        try {
            return gate.onServer(SERVER_TIMEOUT, () -> gate.server().getSaveProperties().getLevelName());
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    @Override
    public SelfState selfState() {
        try {
            return gate.onServer(SERVER_TIMEOUT, () -> {
                ServerPlayerEntity sp = localPlayer();
                if (sp == null) {
                    return new SelfState(0, 0, 0, "unloaded", "unknown", "unknown", "clear",
                            20, 20, "survival", null);
                }
                CompanionEntity c = CompanionManager.ensure(gate.server(), sp);
                ServerWorld world = sp.getServerWorld();
                return new SelfState(
                        c.getX(), c.getY(), c.getZ(),
                        world.getRegistryKey().getValue().toString(),
                        biomeName(world, c.getBlockPos()),
                        timeOfDay(world.getTimeOfDay()),
                        weather(world),
                        c.getHealth(),
                        20.0D,
                        sp.interactionManager.getGameMode().getName(),
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
                ServerPlayerEntity sp = localPlayer();
                if (sp == null) return List.of();
                CompanionEntity c = CompanionManager.ensure(gate.server(), sp);
                ServerWorld world = sp.getServerWorld();
                Vec3d pos = c.getPos();
                List<EntityInfo> out = new ArrayList<>();
                for (LivingEntity e : world.getEntitiesByType(TypeFilter.instanceOf(LivingEntity.class),
                        Box.of(pos, radius * 2, radius * 2, radius * 2),
                        e2 -> e2 != c && e2.isAlive())) {
                    if (out.size() >= MAX_ENTITIES) break;
                    double dist = e.distanceTo(c);
                    if (dist <= radius) {
                        Identifier key = world.getRegistryManager()
                                .get(RegistryKeys.ENTITY_TYPE).getId(e.getType());
                        String type = key != null ? key.getPath() : e.getType().getName().getString();
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
                ServerPlayerEntity sp = localPlayer();
                if (sp == null) return List.of();
                ServerWorld world = sp.getServerWorld();
                Vec3d center = new Vec3d(x, y, z);
                List<EntityInfo> out = new ArrayList<>();
                for (LivingEntity e : world.getEntitiesByType(TypeFilter.instanceOf(LivingEntity.class),
                        Box.of(center, radius * 2, radius * 2, radius * 2),
                        e2 -> e2 != sp && e2.isAlive())) {
                    if (out.size() >= MAX_ENTITIES) break;
                    double dist = Math.sqrt(e.squaredDistanceTo(center));
                    if (dist <= radius) {
                        Identifier key = world.getRegistryManager()
                                .get(RegistryKeys.ENTITY_TYPE).getId(e.getType());
                        String type = key != null ? key.getPath() : e.getType().getName().getString();
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
                ServerPlayerEntity sp = localPlayer();
                if (sp == null) return Optional.empty();
                CompanionEntity c = CompanionManager.ensure(gate.server(), sp);
                ServerWorld world = sp.getServerWorld();
                BlockPos center = c.getBlockPos();
                int r = (int) Math.ceil(Math.min(radius, 64.0D));
                BlockInfo best = null;
                double bestDistSq = Double.MAX_VALUE;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        for (int dy = -r; dy <= r; dy++) {
                            Block block = world.getBlockState(center.add(dx, dy, dz)).getBlock();
                            Identifier key = world.getRegistryManager().get(RegistryKeys.BLOCK).getId(block);
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
                ServerPlayerEntity sp = localPlayer();
                if (sp == null) return List.of();
                CompanionEntity c = CompanionManager.ensure(gate.server(), sp);
                ServerWorld world = sp.getServerWorld();
                BlockPos center = c.getBlockPos();
                int r = (int) Math.ceil(Math.min(radius, 32.0D));
                java.util.LinkedHashSet<String> types = new java.util.LinkedHashSet<>();
                outer:
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        for (int dy = -r; dy <= r; dy++) {
                            Block block = world.getBlockState(center.add(dx, dy, dz)).getBlock();
                            if (block == Blocks.AIR) continue;
                            Identifier key = world.getRegistryManager().get(RegistryKeys.BLOCK).getId(block);
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
                ServerPlayerEntity sp = localPlayer();
                if (sp == null) return false;
                CompanionEntity c = CompanionManager.ensure(gate.server(), sp);
                return c.squaredDistanceTo(x, y, z) <= closeness * closeness;
            });
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public ActionResult breakBlockAt(double x, double y, double z) {
        return onServerAction(() -> {
            ServerPlayerEntity sp = localPlayer();
            if (sp == null) return ActionResult.failed("No local player.");
            ServerWorld world = sp.getServerWorld();
            BlockPos pos = BlockPos.ofFloored(x, y, z);
            Block block = world.getBlockState(pos).getBlock();
            if (block == Blocks.AIR) {
                return ActionResult.blocked("No block at " + pos.getX() + ", "
                        + pos.getY() + ", " + pos.getZ() + ".");
            }
            world.breakBlock(pos, false);
            Identifier key = world.getRegistryManager().get(RegistryKeys.BLOCK).getId(block);
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
            ServerPlayerEntity target = gate.server().getPlayerManager().getPlayer(playerName);
            if (target == null) {
                return ActionResult.blocked("Player '" + playerName + "' is not online right now.");
            }
            ServerWorld world = target.getServerWorld();
            Item resolved = world.getRegistryManager().get(RegistryKeys.ITEM).get(Identifier.tryParse(item));
            if (resolved == null || resolved == Items.AIR) {
                return ActionResult.failed("Unknown item '" + item + "'.");
            }
            CompanionInventory inv = inventoryForWorld();
            if (inv == null) return ActionResult.failed("Inventory is unavailable.");
            try {
                if (inv.count(item) < count) {
                    return ActionResult.blocked("Not carrying enough " + item + " to give.");
                }
                world.spawnEntity(new ItemEntity(world, target.getX(), target.getY() + 1.0D,
                        target.getZ(), new ItemStack(resolved, count)));
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
            CompanionEntity c = CompanionManager.ensure(gate.server(), localPlayer());
            ServerWorld world = (ServerWorld) c.getWorld();
            Item resolved = world.getRegistryManager().get(RegistryKeys.ITEM).get(Identifier.tryParse(item));
            if (resolved == null || resolved == Items.AIR) {
                return ActionResult.failed("Unknown item '" + item + "'.");
            }
            CompanionInventory inv = inventoryForWorld();
            if (inv == null) return ActionResult.failed("Inventory is unavailable.");
            try {
                if (inv.count(item) < count) {
                    return ActionResult.blocked("Not carrying enough " + item + " to drop.");
                }
                Vec3d p = c.getPos();
                world.spawnEntity(new ItemEntity(world, p.x, p.y + 0.5D, p.z,
                        new ItemStack(resolved, count)));
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
            BlockPos target = BlockPos.ofFloored(
                    c.getX() + dx * distance, c.getY(), c.getZ() + dz * distance);
            c.setMoveTarget(target, 1.0D);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("distance", distance);
            return ActionResult.success("Moving away " + distance + " blocks.", data);
        });
    }

    @Override
    public ActionResult lookAtPlayer(String playerName) {
        return onServerAction(() -> {
            ServerPlayerEntity target = gate.server().getPlayerManager().getPlayer(playerName);
            if (target == null) {
                return ActionResult.blocked("Player '" + playerName + "' is not online right now.");
            }
            CompanionEntity c = CompanionManager.ensure(gate.server(), localPlayer());
            c.getLookControl().lookAt(target, 90.0F, 90.0F);
            return ActionResult.success("Looking at " + playerName + ".");
        });
    }

    @Override
    public ActionResult fleeFromEntity(String entityType, double distance) {
        return onServerAction(() -> {
            CompanionEntity c = CompanionManager.ensure(gate.server(), localPlayer());
            ServerWorld world = (ServerWorld) c.getWorld();
            LivingEntity threat = nearestEntityOfType(world, c.getBlockPos(), entityType, 32.0D);
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
            BlockPos target = BlockPos.ofFloored(
                    c.getX() + dx / len * distance, c.getY(), c.getZ() + dz / len * distance);
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
            ServerWorld world = (ServerWorld) c.getWorld();
            LivingEntity target = nearestEntityOfType(world, c.getBlockPos(), entityType, 8.0D);
            if (target == null) {
                return ActionResult.blocked("No " + entityType + " within 8 blocks.");
            }
            boolean hit = c.tryAttack(target);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", entityType);
            return hit ? ActionResult.success("Attacked " + entityType + ".", data)
                    : ActionResult.failed("The attack on " + entityType + " missed or was blocked.");
        });
    }

    @Override
    public ActionResult defendPlayer(String playerName, double distance) {
        return onServerAction(() -> {
            ServerPlayerEntity target = gate.server().getPlayerManager().getPlayer(playerName);
            if (target == null) {
                return ActionResult.blocked("Player '" + playerName + "' is not online right now.");
            }
            ServerWorld world = target.getServerWorld();
            LivingEntity hostile = nearestHostileNear(world, target.getBlockPos(), distance);
            if (hostile == null) {
                return ActionResult.blocked("No hostiles near " + playerName + ".");
            }
            CompanionEntity c = CompanionManager.ensure(gate.server(), localPlayer());
            boolean hit = c.tryAttack(hostile);
            Identifier key = world.getRegistryManager().get(RegistryKeys.ENTITY_TYPE).getId(hostile.getType());
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
                ServerPlayerEntity p = gate.server().getPlayerManager().getPlayer(playerName);
                if (p == null) return new PlayerState(playerName, 0, 0, 0, -1, 0, false);
                ServerPlayerEntity me = localPlayer();
                CompanionEntity c = me == null ? null : CompanionManager.ensure(gate.server(), me);
                double dist = c == null ? -1 : c.distanceTo(p);
                return new PlayerState(playerName, p.getX(), p.getY(), p.getZ(), dist, p.getHealth(), true);
            });
        } catch (RuntimeException e) {
            return new PlayerState(playerName, 0, 0, 0, -1, 0, false);
        }
    }

    @Override
    public List<String> nearbyPlayers(double radius) {
        try {
            return gate.onServer(SERVER_TIMEOUT, () -> {
                ServerPlayerEntity sp = localPlayer();
                if (sp == null) return List.of();
                CompanionEntity c = CompanionManager.ensure(gate.server(), sp);
                List<String> names = new ArrayList<>();
                for (ServerPlayerEntity p : gate.server().getPlayerManager().getPlayerList()) {
                    if (p != sp && c.squaredDistanceTo(p) <= radius * radius) {
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
            c.setMoveTarget(BlockPos.ofFloored(x, y, z), closeness);
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
            ServerPlayerEntity target = gate.server().getPlayerManager().getPlayer(playerName);
            if (target == null) {
                return ActionResult.blocked("Player '" + playerName + "' is not online right now.");
            }
            CompanionEntity c = CompanionManager.ensure(gate.server(), localPlayer());
            c.setMoveTarget(target.getBlockPos(), closeness);
            return ActionResult.success("On my way to " + playerName + ".");
        });
    }

    @Override
    public ActionResult followPlayer(String playerName, double distance) {
        return onServerAction(() -> {
            ServerPlayerEntity target = gate.server().getPlayerManager().getPlayer(playerName);
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
                ServerPlayerEntity sp = localPlayer();
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

    private ActionResult onServerAction(Supplier<ActionResult> task) {
        try {
            return gate.onServer(SERVER_TIMEOUT, task);
        } catch (RuntimeException e) {
            return ActionResult.failed("Companion action failed: " + e.getMessage());
        }
    }

    private ServerPlayerEntity localPlayer() {
        return gate.server().getPlayerManager().getPlayer(mc.getSession().getUsername());
    }

    private CompanionInventory inventoryForWorld() {
        String worldId = worldId();
        return inventories.computeIfAbsent(worldId, w -> {
            Path memoryDir = Path.of(".").toAbsolutePath().resolve("clankerjockey/memory");
            try {
                return CompanionInventory.forWorld(memoryDir, w);
            } catch (IOException e) {
                return null;
            }
        });
    }

    private static String biomeName(ServerWorld world, BlockPos pos) {
        try {
            Identifier key = world.getRegistryManager().get(RegistryKeys.BIOME)
                    .getId(world.getBiome(pos).value());
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

    private static String weather(ServerWorld world) {
        if (world.isThundering()) return "thunderstorm";
        if (world.isRaining()) return "rain";
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
        double yaw = Math.toRadians(c.getYaw());
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

    private LivingEntity nearestEntityOfType(ServerWorld world, BlockPos center, String type, double radius) {
        List<LivingEntity> found = world.getEntitiesByType(TypeFilter.instanceOf(LivingEntity.class),
                Box.of(center.toCenterPos(), radius * 2, radius * 2, radius * 2),
                e -> e.isAlive() && matchesType(world, e, type));
        return found.stream().min(Comparator.comparingDouble(
                e -> e.squaredDistanceTo(center.getX(), center.getY(), center.getZ()))).orElse(null);
    }

    private LivingEntity nearestHostileNear(ServerWorld world, BlockPos center, double radius) {
        List<LivingEntity> found = world.getEntitiesByType(TypeFilter.instanceOf(LivingEntity.class),
                Box.of(center.toCenterPos(), radius * 2, radius * 2, radius * 2),
                e -> e.isAlive() && e instanceof Monster);
        return found.stream().min(Comparator.comparingDouble(
                e -> e.squaredDistanceTo(center.getX(), center.getY(), center.getZ()))).orElse(null);
    }

    private static boolean matchesType(ServerWorld world, LivingEntity e, String type) {
        Identifier key = world.getRegistryManager().get(RegistryKeys.ENTITY_TYPE).getId(e.getType());
        return key != null && (key.getPath().equalsIgnoreCase(type) || key.toString().equalsIgnoreCase(type));
    }
}
