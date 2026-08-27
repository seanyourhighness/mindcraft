package net.clankerjockey.mod.agent;

import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.clankerjockey.core.agent.AgentLoop;
import net.clankerjockey.core.agent.AgentLoopConfig;
import net.clankerjockey.core.engine.InferenceEngine;
import net.clankerjockey.core.events.EventPriority;
import net.clankerjockey.core.events.ProactivePolicy;
import net.clankerjockey.core.events.ReflexLayer;
import net.clankerjockey.core.events.SalienceGate;
import net.clankerjockey.core.events.SemanticEvent;
import net.clankerjockey.core.events.WorldAwareness;
import net.clankerjockey.core.memory.ChatSession;
import net.clankerjockey.core.tasks.AgentTask;
import net.clankerjockey.core.tasks.CollectTaskWorker;
import net.clankerjockey.core.tools.CoreTools;
import net.clankerjockey.core.tools.ToolExecutor;
import net.clankerjockey.core.tools.ToolRegistry;
import net.clankerjockey.core.world.EntityInfo;
import net.clankerjockey.core.world.PlayerState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fabric client-side agent entrypoint: owns the tool registry, executor,
 * agent loop and world adapter, listens for outgoing chat, and runs each turn
 * on a worker thread. Vera's final answer is broadcast through the integrated
 * server so it appears as companion speech.
 */
public final class ClankerJockeyAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("clankerjockey-agent");

    private static volatile ClankerJockeyAgent instance;

    private final InferenceEngine engine;
    private final AgentLoop loop;
    private final FabricWorldAdapter world;
    private final Path memoryDir;
    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();
    private final SalienceGate salience = new SalienceGate();
    private final Map<String, ProactivePolicy> proactivePolicies = new ConcurrentHashMap<>();
    private final WorldAwareness worldAwareness = new WorldAwareness();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clankerjockey-agent");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService taskScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "clankerjockey-tasks");
        t.setDaemon(true);
        return t;
    });

    private ClankerJockeyAgent(InferenceEngine engine) {
        this.engine = engine;
        this.world = new FabricWorldAdapter(MinecraftClient.getInstance());
        this.memoryDir = Path.of(".").toAbsolutePath().resolve("clankerjockey/memory");
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(CoreTools.all());
        ToolExecutor executor = new ToolExecutor(registry);
        this.loop = new AgentLoop(engine, registry, executor, AgentLoopConfig.defaults(),
                ChatSession.SYSTEM_PROMPT);
        ClientSendMessageEvents.CHAT.register(this::onClientChat);
        ServerLivingEntityEvents.AFTER_DAMAGE.register(
                (entity, source, baseAmount, damageTaken, blocked) -> onLivingDamage(entity, damageTaken));
        ServerLivingEntityEvents.AFTER_DEATH.register(this::onLivingDeath);
        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> onPlayerJoin(handler.getPlayer().getName().getString()));
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> onPlayerLeave(handler.getPlayer().getName().getString()));
        taskScheduler.scheduleWithFixedDelay(this::tickCompanion, 2, 1, TimeUnit.SECONDS);
        LOGGER.info("[clankerjockey] agent loop ready with {} tools", registry.size());
    }

    /**
     * Per-second companion tick: run LLM-free reflexes first (interrupting
     * active tasks on danger), then advance long-running tasks.
     */
    private void tickCompanion() {
        try {
            MinecraftServer server = MinecraftClient.getInstance().getServer();
            if (server == null) return;
            server.execute(() -> {
                ReflexLayer.ReflexResult reflex = new ReflexLayer().tick(world).orElse(null);
                if (reflex != null) {
                    for (ChatSession session : sessions.values()) {
                        for (AgentTask t : session.tasks().active()) {
                            t.requestCancel(); // danger interrupts current work
                        }
                        session.events().add(reflex.toEvent());
                    }
                }
                ChatSession local = sessions.get(MinecraftClient.getInstance().getSession().getUsername());
                if (local != null) {
                    SemanticEvent aware = worldAwareness.tick(world.selfState()).orElse(null);
                    if (aware != null) {
                        maybeProactive(local, aware);
                    }
                }
                CollectTaskWorker collectWorker = new CollectTaskWorker(world);
                for (ChatSession session : sessions.values()) {
                    for (AgentTask t : session.tasks().active()) {
                        if (CollectTaskWorker.TYPE.equals(t.data().get("type"))) {
                            Object block = t.data().get("block");
                            Object count = t.data().get("count");
                            if (block instanceof String b && count instanceof Number n) {
                                collectWorker.tick(t, b, n.intValue());
                            }
                        }
                    }
                }
                proactiveTick(server);
            });
        } catch (Exception e) {
            LOGGER.debug("[clankerjockey] task tick failed", e);
        }
    }

    /** Ambient awareness: react to a hostile near the player, at most once per cooldown. */
    private void proactiveTick(MinecraftServer server) {
        try {
            String localName = MinecraftClient.getInstance().getSession().getUsername();
            ChatSession session = sessions.get(localName);
            if (session == null) return;
            PlayerState player = world.playerState(localName);
            if (!player.online()) return;
            EntityInfo nearest = world.nearbyEntitiesNear(player.x(), player.y(), player.z(), 12.0D).stream()
                    .filter(EntityInfo::hostile)
                    .min(java.util.Comparator.comparingDouble(EntityInfo::distance))
                    .orElse(null);
            if (nearest == null) return;
            SemanticEvent ev = new SemanticEvent(EventPriority.P2, "HOSTILE_NEAR_PLAYER",
                    "a " + nearest.type() + " is " + String.format(java.util.Locale.ROOT, "%.1f", nearest.distance())
                            + "m from " + localName,
                    nearest.distance(), System.currentTimeMillis(), java.util.Map.of());
            if (!salience.assess(ev).shouldNotify()) return;
            maybeProactive(session, ev);
        } catch (Exception e) {
            LOGGER.debug("[clankerjockey] proactive tick failed", e);
        }
    }

    /** Record a salient event and, within the proactive cooldown, react in character. */
    private void maybeProactive(ChatSession session, SemanticEvent ev) {
        try {
            session.events().add(ev);
            ProactivePolicy policy = proactivePolicies.computeIfAbsent(session.playerId(), k -> new ProactivePolicy());
            if (!policy.shouldTrigger(System.currentTimeMillis())) return;
            worker.submit(() -> {
                try {
                    String answer = session.replyWithNotice("You noticed: " + ev.description(), loop, world);
                    sayInChat(answer);
                } catch (Exception e) {
                    LOGGER.debug("[clankerjockey] proactive turn failed", e);
                }
            });
        } catch (Exception e) {
            LOGGER.debug("[clankerjockey] proactive event failed", e);
        }
    }

    private String localName() {
        return MinecraftClient.getInstance().getSession().getUsername();
    }

    private void onLivingDamage(LivingEntity entity, float damage) {
        if (!(entity instanceof PlayerEntity player)) return;
        if (!player.getName().getString().equals(localName())) return;
        ChatSession session = sessions.get(localName());
        if (session == null) return;
        long hp = Math.round(player.getHealth());
        maybeProactive(session, SemanticEvent.of(EventPriority.P0, "PLAYER_DAMAGED",
                "You took " + Math.round(damage) + " damage; health is now " + hp + "/20."));
    }

    private void onLivingDeath(LivingEntity entity, net.minecraft.entity.damage.DamageSource source) {
        if (!(entity instanceof PlayerEntity player)) return;
        if (!player.getName().getString().equals(localName())) return;
        ChatSession session = sessions.get(localName());
        if (session == null) return;
        maybeProactive(session, SemanticEvent.of(EventPriority.P0, "PLAYER_DEATH",
                "You died at " + Math.round(player.getX()) + ", " + Math.round(player.getY())
                        + ", " + Math.round(player.getZ()) + "."));
    }

    private void onPlayerJoin(String name) {
        for (ChatSession session : sessions.values()) {
            session.events().add(SemanticEvent.of(EventPriority.P3, "PLAYER_JOIN",
                    name + " joined the world."));
        }
    }

    private void onPlayerLeave(String name) {
        for (ChatSession session : sessions.values()) {
            session.events().add(SemanticEvent.of(EventPriority.P3, "PLAYER_LEAVE",
                    name + " left the world."));
        }
    }

    /** Start the agent (client init, after the engine is healthy). */
    public static void start(InferenceEngine engine) {
        if (instance == null) {
            instance = new ClankerJockeyAgent(engine);
        }
    }

    private void onClientChat(String message) {
        if (message == null || message.isBlank() || message.startsWith("/")) {
            return;
        }
        String playerName = MinecraftClient.getInstance().getSession().getUsername();
        worker.submit(() -> handleChat(playerName, message));
    }

    private void handleChat(String playerName, String message) {
        try {
            if (engine == null || !engine.isRunning()) {
                LOGGER.debug("[clankerjockey] chat ignored, engine not running");
                return;
            }
            ChatSession session = sessions.computeIfAbsent(playerName, p -> {
                try {
                    return new ChatSession(engine, memoryDir, world.worldId(), p);
                } catch (IOException e) {
                    LOGGER.warn("[clankerjockey] could not open memory for {}", p, e);
                    return null;
                }
            });
            if (session == null) {
                return;
            }
            synchronized (session) {
                String answer = session.replyWithAgent(message, loop, world);
                sayInChat(answer);
            }
        } catch (Exception e) {
            LOGGER.warn("[clankerjockey] agent turn failed", e);
        }
    }

    /** Broadcast Vera's line through the integrated server (server thread). */
    private void sayInChat(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        MinecraftServer server = MinecraftClient.getInstance().getServer();
        if (server == null) {
            return;
        }
        String line = "Vera: " + text.strip();
        server.execute(() ->
                server.getPlayerManager().broadcast(Text.literal(line), false));
    }
}
