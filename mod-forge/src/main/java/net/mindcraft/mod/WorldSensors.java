package net.mindcraft.mod;

import net.mindcraft.core.agent.AgentRuntime;
import net.mindcraft.core.agent.Signal;
import net.mindcraft.core.agent.Watch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * The perception layer: Minecraft-native sensors that turn world events into
 * {@link Signal}s for the {@link AgentRuntime}.
 *
 * <p>Handlers are registered on the <b>game</b> bus ({@link
 * MinecraftForge#EVENT_BUS}) in {@link #register()}, because Forge game
 * events (entity, interact, block, chat, tick) post there — not on the mod
 * bus that {@code @Mod.EventBusSubscriber} targets.
 *
 * <ul>
 *   <li>{@link EntityJoinLevelEvent} — a living mob spawning within 32 blocks
 *       of the local player → {@link Signal#mob}</li>
 *   <li>{@link PlayerInteractEvent.RightClickItem} — item usage →
 *       {@link Signal#itemUse}</li>
 *   <li>{@link ClientChatEvent} — the player typed a chat message →
 *       {@link Signal#chat}</li>
 *   <li>{@link BlockEvent.BreakEvent} — mining → {@link Signal#blockBreak}</li>
 *   <li>client tick (1 Hz) — biome change at the player's position →
 *       {@link Signal#biome}</li>
 * </ul>
 *
 * <p>Structure detection is left to the LLM (it sees the biome + nearby
 * context in the prompt) rather than a separate structure poller: the
 * lightweight design deliberately keeps one poller.
 */
public final class WorldSensors {

    /** Mob detection radius around the local player. */
    private static final double MOB_RADIUS = 32.0;
    /** Biome poll cadence in client ticks (20 = 1 Hz). */
    private static final int BIOME_POLL_TICKS = 20;

    private final AgentRuntime agent;
    private int tickCounter;
    private String lastBiome;

    public WorldSensors(AgentRuntime agent) {
        this.agent = agent;
    }

    /** Register all handlers on the game bus. Call once on the client thread. */
    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return; // only care about the client's own world
        }
        Entity e = event.getEntity();
        if (e instanceof Player || !(e instanceof LivingEntity)) {
            return;
        }
        LocalPlayer me = localPlayer();
        if (me == null || e.distanceToSqr(me) > MOB_RADIUS * MOB_RADIUS) {
            return;
        }
        String id = event.getLevel().registryAccess()
                .registryOrThrow(Registries.ENTITY_TYPE)
                .getKey(e.getType())
                .toString();
        agent.observe(Signal.mob(id));
    }

    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        String id = event.getLevel().registryAccess()
                .registryOrThrow(Registries.ITEM)
                .getKey(event.getItemStack().getItem())
                .toString();
        agent.observe(Signal.itemUse(id));
    }

    @SubscribeEvent
    public void onChat(ClientChatEvent event) {
        agent.observe(Signal.chat(event.getMessage()));
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        String id = event.getLevel().registryAccess()
                .registryOrThrow(Registries.BLOCK)
                .getKey(event.getState().getBlock())
                .toString();
        agent.observe(Signal.blockBreak(id));
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++tickCounter % BIOME_POLL_TICKS != 0) {
            return;
        }
        LocalPlayer me = localPlayer();
        if (me == null || me.level() == null) {
            return;
        }
        BlockPos pos = me.blockPosition();
        LevelChunk chunk = me.level().getChunkAt(pos);
        if (chunk == null) {
            return;
        }
        // Biome at the player's position via the chunk's noise biome.
        Holder<Biome> holder = me.level().getBiomeManager().getBiome(pos);
        Biome biome = holder.value();
        String id = me.level().registryAccess()
                .registryOrThrow(Registries.BIOME)
                .getKey(biome)
                .toString();
        if (!id.equals(lastBiome)) {
            lastBiome = id;
            agent.observe(Signal.biome(id));
        }
    }

    private static LocalPlayer localPlayer() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null ? null : mc.player;
    }

    /**
     * The default watch set: the behaviors Sean named — mobs, biomes,
     * keywords, item usage. Each one pings the LLM at most once per
     * cooldown; the LLM decides whether to speak or act.
     */
    public static AgentRuntime defaultWatches(AgentRuntime runtime) {
        runtime
                .watch(Watch.builder().kind(Signal.Kind.MOB)
                        .subjects("minecraft:creeper", "minecraft:enderman",
                                "minecraft:skeleton", "minecraft:zombie_pigman")
                        .cooldownMs(15_000)
                        .note("A mob of interest spawned nearby. Warn the player or react in character.")
                        .build())
                .watch(Watch.builder().kind(Signal.Kind.BIOME)
                        .any()
                        .cooldownMs(30_000)
                        .note("The player entered a new biome. Comment on it or react.")
                        .build())
                .watch(Watch.builder().kind(Signal.Kind.CHAT)
                        .contains("help")
                        .cooldownMs(10_000)
                        .note("The player asked for help in chat. Assist: suggest a tool call or advice.")
                        .build())
                .watch(Watch.builder().kind(Signal.Kind.ITEM_USE)
                        .subjects("minecraft:diamond_pickaxe", "minecraft:ender_pearl")
                        .cooldownMs(20_000)
                        .note("The player used a notable item.")
                        .build())
                .watch(Watch.builder().kind(Signal.Kind.BLOCK_BREAK)
                        .subjects("minecraft:diamond_ore", "minecraft:ancient_debris")
                        .cooldownMs(10_000)
                        .note("The player mined a valuable ore. React.")
                        .build());
        return runtime;
    }
}
