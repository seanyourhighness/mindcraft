package net.mindcraft.core.world;

import java.util.List;
import java.util.Optional;

/**
 * Loader-neutral abstraction over the Minecraft world as perceived and acted
 * upon by the companion. Each game loader (Forge, Fabric) provides its own
 * implementation; core tools and the agent loop only ever talk to this
 * interface, keeping the agent architecture loader-agnostic.
 *
 * <p>Implementations must be safe to call from the agent loop's worker
 * thread; they internally hop onto the game/server thread where required.</p>
 */
public interface AgentWorld {

    /** Current companion state (position, dimension, following status...). */
    SelfState selfState();

    /** Entities within {@code radius} blocks of the companion. */
    List<EntityInfo> nearbyEntities(double radius);

    /** Entities within {@code radius} blocks of an arbitrary position. */
    List<EntityInfo> nearbyEntitiesNear(double x, double y, double z, double radius);

    /** Companion inventory snapshot (virtual inventory for NPC bodies). */
    InventoryView inventory();

    /** State of a named player, or an offline/unknown marker. */
    PlayerState playerState(String playerName);

    /** Names of players within {@code radius} blocks. */
    List<String> nearbyPlayers(double radius);

    /**
     * Find the nearest block with the given block id (e.g. {@code "iron_ore"})
     * within {@code radius} blocks of the companion.
     */
    Optional<BlockInfo> findNearbyBlock(String blockId, double radius);

    /** Distinct block types within a radius of the companion (air excluded). */
    List<String> visibleBlockTypes(double radius);

    /** True when the companion is within {@code closeness} of a position. */
    boolean isNear(double x, double y, double z, double closeness);

    /** Break a block at a position (no item drops); returns the broken block id. */
    ActionResult breakBlockAt(double x, double y, double z);

    /** Add items to the companion's virtual inventory (e.g. from a collection). */
    ActionResult addItem(String item, int count);

    /** Remove items from the virtual inventory; blocked when not carrying enough. */
    ActionResult removeItem(String item, int count);

    /** Give items to a player: remove from inventory and spawn them in-world. */
    ActionResult giveItemToPlayer(String playerName, String item, int count);

    /** Drop items from the inventory as an in-world item entity near the companion. */
    ActionResult dropItem(String item, int count);

    /** Equip an item from the virtual inventory (null/blank clears). */
    ActionResult equipItem(String item);

    /** Walk away from the current position by a distance in a free direction. */
    ActionResult moveAway(double distance);

    /** Turn the companion's head/body toward a player. */
    ActionResult lookAtPlayer(String playerName);

    /** Walk away from the nearest entity of a type. */
    ActionResult fleeFromEntity(String entityType, double distance);

    /** Attack the nearest entity of a type. */
    ActionResult attackEntity(String entityType);

    /** Attack hostiles near a player to protect them. */
    ActionResult defendPlayer(String playerName, double distance);

    /** Walk the companion to coordinates; returns when close or blocked. */
    ActionResult goTo(double x, double y, double z, double closeness);

    /** Walk the companion to a player; fails/blocked if the player is absent. */
    ActionResult goToPlayer(String playerName, double closeness);

    /** Start persistent follow behavior at the given distance. */
    ActionResult followPlayer(String playerName, double distance);

    /** Stop any active follow behavior. */
    ActionResult stopFollowing();

    /** True when follow behavior is currently active. */
    boolean isFollowing();

    /** Name of the player being followed, if any. */
    Optional<String> followingPlayer();
}
