package net.clankerjockey.mod.agent;

import net.clankerjockey.core.agent.AgentLoop;
import net.clankerjockey.core.agent.AgentLoopConfig;
import net.clankerjockey.core.agent.Signal;
import net.clankerjockey.core.engine.InferenceEngine;
import net.clankerjockey.core.events.EventPriority;
import net.clankerjockey.core.events.ProactivePolicy;
import net.clankerjockey.core.events.ReflexLayer;
import net.clankerjockey.core.events.SalienceGate;
import net.clankerjockey.core.events.SemanticEvent;
import net.clankerjockey.core.events.SignalBridge;
import net.clankerjockey.core.events.WorldAwareness;
import net.clankerjockey.core.memory.ChatSession;
import net.clankerjockey.core.tasks.AgentTask;
import net.clankerjockey.core.tasks.CollectTaskWorker;
import net.clankerjockey.core.tools.CoreTools;
import net.clankerjockey.core.tools.ToolExecutor;
import net.clankerjockey.core.tools.ToolRegistry;
import net.clankerjockey.core.world.EntityInfo;
import net.clankerjockey.core.world.PlayerState;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Client-side agent entrypoint: owns the tool registry, executor, agent loop
 * and world adapter, listens for player chat, and runs each turn on a worker
 * thread (never blocking the render thread). Vera's final answer is broadcast
 * through the integrated server so it appears as companion speech.
 */
public final class ClankerJockeyAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("clankerjockey-agent");

    private static volatile ClankerJockeyAgent instance;

    private final InferenceEngine engine;
    private final AgentLoop loop;
    private final ForgeWorldAdapter world;
    private final Path memoryDir;
    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();
    private final SalienceGate salience = new SalienceGate();
    private final Map<String, ProactivePolicy> proactivePolicies = new ConcurrentHashMap<>();
    private final WorldAwareness worldAwareness = new WorldAwareness();
    private final SignalBridge signalBridge = new SignalBridge(SignalBridge.defaultWatches());
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

    private ClankerJockeyAgent(InferenceEngine engine, Minecraft mc) {
        this.engine = engine;
        this.world = new ForgeWorldAdapter(mc);
        this.memoryDir = Path.of(".").toAbsolutePath().resolve("clankerjockey/memory");
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(CoreTools.all());
        ToolExecutor executor = new ToolExecutor(registry);
        this.loop = new AgentLoop(engine, registry, executor, AgentLoopConfig.defaults(),
                ChatSession.SYSTEM_PROMPT);
        MinecraftForge.EVENT_BUS.register(this);
        taskScheduler.scheduleWithFixedDelay(this::tickCompanion, 2, 1, TimeUnit.SECONDS);
        LOGGER.info("[clankerjockey] agent loop ready with {} tools", registry.size());
    }

    /**
     * Per-second companion tick: run LLM-free reflexes first (interrupting
     * active tasks on danger), then advance long-running tasks.
     */
    private void tickCompanion() {
        try {
            MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
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
                ChatSession local = sessions.get(Minecraft.getInstance().getUser().getName());
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
            String localName = Minecraft.getInstance().getUser().getName();
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
        return Minecraft.getInstance().getUser().getName();
    }

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!player.getName().getString().equals(localName())) return;
        ChatSession session = sessions.get(localName());
        if (session == null) return;
        long hp = Math.round(player.getHealth());
        maybeProactive(session, SemanticEvent.of(EventPriority.P0, "PLAYER_DAMAGED",
                "You took " + Math.round(event.getAmount()) + " damage; health is now " + hp + "/20."));
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!player.getName().getString().equals(localName())) return;
        ChatSession session = sessions.get(localName());
        if (session == null) return;
        maybeProactive(session, SemanticEvent.of(EventPriority.P0, "PLAYER_DEATH",
                "You died at " + Math.round(player.getX()) + ", " + Math.round(player.getY())
                        + ", " + Math.round(player.getZ()) + "."));
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        String name = event.getEntity().getName().getString();
        for (ChatSession session : sessions.values()) {
            session.events().add(SemanticEvent.of(EventPriority.P3, "PLAYER_JOIN",
                    name + " joined the world."));
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        String name = event.getEntity().getName().getString();
        for (ChatSession session : sessions.values()) {
            session.events().add(SemanticEvent.of(EventPriority.P3, "PLAYER_LEAVE",
                    name + " left the world."));
        }
    }

    /** Start the agent (single-player client setup, after the engine is healthy). */
    public static void start(InferenceEngine engine) {
        if (instance == null) {
            instance = new ClankerJockeyAgent(engine, Minecraft.getInstance());
        }
    }

    public static ClankerJockeyAgent instance() {
        return instance;
    }

    /**
     * Sensor feed (WorldSensors): a curated world signal becomes a gated
     * semantic event, then the proactive path decides whether the companion
     * reacts in character.
     */
    public void onSignal(Signal signal) {
        try {
            Optional<SemanticEvent> event = signalBridge.assess(signal);
            if (event.isEmpty()) return;
            ChatSession session = sessions.get(localName());
            if (session != null) {
                maybeProactive(session, event.get());
            }
        } catch (Exception e) {
            LOGGER.debug("[clankerjockey] sensor signal failed", e);
        }
    }

    /**
     * Run a full agent turn for a voice transcript (STT input) and return the
     * reply so the caller can speak it (TTS). Same memory, tools and loop as
     * typed chat — one seamless agent.
     */
    public static String handleVoice(String transcript) {
        ClankerJockeyAgent a = instance;
        if (a == null || transcript == null || transcript.isBlank()) {
            return null;
        }
        return a.voiceReply(transcript);
    }

    private String voiceReply(String text) {
        String name = localName();
        ChatSession session = sessions.computeIfAbsent(name, p -> {
            try {
                return new ChatSession(engine, memoryDir, world.worldId(), p);
            } catch (IOException e) {
                LOGGER.warn("[clankerjockey] could not open memory for {}", p, e);
                return null;
            }
        });
        if (session == null) return null;
        try {
            synchronized (session) {
                return session.replyWithAgent(text, loop, world);
            }
        } catch (Exception e) {
            LOGGER.warn("[clankerjockey] voice turn failed", e);
            return null;
        }
    }

    @SubscribeEvent
    public void onClientChat(ClientChatEvent event) {
        String message = event.getMessage();
        if (message == null || message.isBlank() || message.startsWith("/")) {
            return;
        }
        String playerName = Minecraft.getInstance().getUser().getName();
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
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            return;
        }
        String line = "Vera: " + text.strip();
        server.execute(() ->
                server.getPlayerList().broadcastSystemMessage(Component.literal(line), false));
    }

    /**
     * Announce a voice-loop state change ("Listening...", "Voice chat off")
     * in chat. Routes through the integrated server when the agent is online
     * (same "Vera:" channel as replies); falls back to a client-side system
     * message when there is no server. Never throws.
     */
    public static void announceVoice(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        ClankerJockeyAgent a = instance;
        if (a != null) {
            a.sayInChat(text);
            return;
        }
        try {
            net.minecraft.client.player.LocalPlayer p = Minecraft.getInstance().player;
            if (p != null) {
                p.sendSystemMessage(Component.literal("ClankerJockey: " + text.strip()));
            }
        } catch (RuntimeException e) {
            LOGGER.debug("clankerjockey voice cue failed", e);
        }
    }
}
