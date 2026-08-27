package net.mindcraft.core.agent;

import net.mindcraft.core.world.ActionResult;
import net.mindcraft.core.world.AgentWorld;
import net.mindcraft.core.world.BlockInfo;
import net.mindcraft.core.world.EntityInfo;
import net.mindcraft.core.world.InventoryView;
import net.mindcraft.core.world.ItemCount;
import net.mindcraft.core.world.PlayerState;
import net.mindcraft.core.world.SelfState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory fake world for agent-loop tests. */
public final class TestWorld implements AgentWorld {

    public SelfState state = new SelfState(1, 64, 2, "overworld", "plains", "day", "clear",
            20, 20, "survival", null);
    public List<EntityInfo> entities = new ArrayList<>();
    public Optional<BlockInfo> nearestBlock = Optional.empty();
    public List<String> visibleBlocks = List.of();
    public final Map<String, Integer> items = new LinkedHashMap<>();
    public String equippedItem;
    public ActionResult goToResult = ActionResult.success("Arrived.");
    public ActionResult goToPlayerResult = ActionResult.success("Arrived at the player.");
    public ActionResult followResult = ActionResult.success("Now following Sean.");
    public ActionResult stopResult = ActionResult.success("Stopped following.");
    public boolean following;
    public String followingName;
    public int inventoryCalls;
    public int goToCalls;
    public int goToPlayerCalls;
    public int followCalls;
    public int stopCalls;
    public int giveCalls;
    public int dropCalls;
    public int equipCalls;
    public int consumeCalls;
    public int moveAwayCalls;
    public int lookAtCalls;
    public int fleeCalls;
    public int attackCalls;
    public int defendCalls;

    @Override
    public SelfState selfState() {
        return new SelfState(state.x(), state.y(), state.z(), state.dimension(), state.biome(),
                state.timeOfDay(), state.weather(), state.health(), state.hunger(),
                state.gameMode(), following ? followingName : null);
    }

    @Override
    public List<EntityInfo> nearbyEntities(double radius) {
        return nearbyEntitiesNear(state.x(), state.y(), state.z(), radius);
    }

    @Override
    public List<EntityInfo> nearbyEntitiesNear(double x, double y, double z, double radius) {
        List<EntityInfo> out = new ArrayList<>();
        for (EntityInfo e : entities) {
            double dx = e.x() - x;
            double dy = e.y() - y;
            double dz = e.z() - z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist <= radius) {
                out.add(new EntityInfo(e.type(), dist, e.x(), e.y(), e.z(), e.hostile(), e.direction()));
            }
        }
        out.sort(java.util.Comparator.comparingDouble(EntityInfo::distance));
        return List.copyOf(out);
    }

    @Override
    public InventoryView inventory() {
        inventoryCalls++;
        List<ItemCount> counts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : items.entrySet()) {
            counts.add(new ItemCount(e.getKey(), e.getValue(), -1));
        }
        return new InventoryView(counts, equippedItem == null ? List.of() : List.of(equippedItem));
    }

    @Override
    public PlayerState playerState(String playerName) {
        return new PlayerState(playerName, state.x(), state.y(), state.z(), 0, 20, true);
    }

    @Override
    public List<String> nearbyPlayers(double radius) {
        return List.of();
    }

    @Override
    public ActionResult goTo(double x, double y, double z, double closeness) {
        goToCalls++;
        following = false;
        followingName = null;
        // Simulate arrival: teleport the companion to the target.
        state = new SelfState(x, y, z, state.dimension(), state.biome(), state.timeOfDay(),
                state.weather(), state.health(), state.hunger(), state.gameMode(), null);
        return goToResult;
    }

    @Override
    public ActionResult goToPlayer(String playerName, double closeness) {
        goToPlayerCalls++;
        following = false;
        followingName = null;
        return goToPlayerResult;
    }

    @Override
    public Optional<BlockInfo> findNearbyBlock(String blockId, double radius) {
        return nearestBlock;
    }

    @Override
    public List<String> visibleBlockTypes(double radius) {
        return List.copyOf(visibleBlocks);
    }

    @Override
    public boolean isNear(double x, double y, double z, double closeness) {
        double dx = state.x() - x;
        double dy = state.y() - y;
        double dz = state.z() - z;
        return dx * dx + dy * dy + dz * dz <= closeness * closeness;
    }

    @Override
    public ActionResult breakBlockAt(double x, double y, double z) {
        if (nearestBlock.isEmpty()) {
            return ActionResult.failed("Nothing to break.");
        }
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("block", nearestBlock.get().block());
        data.put("x", x);
        data.put("y", y);
        data.put("z", z);
        return ActionResult.success("Broke " + nearestBlock.get().block() + ".", data);
    }

    @Override
    public ActionResult addItem(String item, int count) {
        items.merge(item, count, Integer::sum);
        return ActionResult.success("Added " + count + " " + item + ".");
    }

    @Override
    public ActionResult removeItem(String item, int count) {
        Integer have = items.get(item);
        if (have == null || have < count) {
            return ActionResult.blocked("Not carrying enough " + item + ".");
        }
        items.put(item, have - count);
        consumeCalls++;
        return ActionResult.success("Removed " + count + " " + item + ".");
    }

    @Override
    public ActionResult giveItemToPlayer(String playerName, String item, int count) {
        Integer have = items.get(item);
        if (have == null || have < count) {
            return ActionResult.blocked("Not carrying enough " + item + " to give.");
        }
        items.put(item, have - count);
        giveCalls++;
        return ActionResult.success("Gave " + count + " " + item + " to " + playerName + ".");
    }

    @Override
    public ActionResult dropItem(String item, int count) {
        Integer have = items.get(item);
        if (have == null || have < count) {
            return ActionResult.blocked("Not carrying enough " + item + " to drop.");
        }
        items.put(item, have - count);
        dropCalls++;
        return ActionResult.success("Dropped " + count + " " + item + ".");
    }

    @Override
    public ActionResult equipItem(String item) {
        if (item == null || item.isBlank()) {
            equippedItem = null;
            equipCalls++;
            return ActionResult.success("Unequipped everything.");
        }
        Integer have = items.get(item);
        if (have == null || have < 1) {
            return ActionResult.blocked("Not carrying " + item + " to equip.");
        }
        equippedItem = item;
        equipCalls++;
        return ActionResult.success("Equipped " + item + ".");
    }

    @Override
    public ActionResult moveAway(double distance) {
        moveAwayCalls++;
        return ActionResult.success("Moving away " + distance + " blocks.");
    }

    @Override
    public ActionResult lookAtPlayer(String playerName) {
        lookAtCalls++;
        return ActionResult.success("Looking at " + playerName + ".");
    }

    @Override
    public ActionResult fleeFromEntity(String entityType, double distance) {
        boolean found = entities.stream().anyMatch(e -> e.type().equalsIgnoreCase(entityType));
        if (!found) {
            return ActionResult.blocked("No " + entityType + " nearby to flee from.");
        }
        fleeCalls++;
        return ActionResult.success("Fleeing from " + entityType + ".");
    }

    @Override
    public ActionResult attackEntity(String entityType) {
        boolean found = entities.stream().anyMatch(e -> e.type().equalsIgnoreCase(entityType));
        if (!found) {
            return ActionResult.blocked("No " + entityType + " within 8 blocks.");
        }
        attackCalls++;
        return ActionResult.success("Attacked " + entityType + ".");
    }

    @Override
    public ActionResult defendPlayer(String playerName, double distance) {
        boolean found = entities.stream().anyMatch(EntityInfo::hostile);
        if (!found) {
            return ActionResult.blocked("No hostiles near " + playerName + ".");
        }
        defendCalls++;
        return ActionResult.success("Defended " + playerName + ".");
    }

    @Override
    public ActionResult followPlayer(String playerName, double distance) {
        followCalls++;
        following = true;
        followingName = playerName;
        return followResult;
    }

    @Override
    public ActionResult stopFollowing() {
        stopCalls++;
        following = false;
        followingName = null;
        return stopResult;
    }

    @Override
    public boolean isFollowing() {
        return following;
    }

    @Override
    public Optional<String> followingPlayer() {
        return Optional.ofNullable(followingName);
    }
}
