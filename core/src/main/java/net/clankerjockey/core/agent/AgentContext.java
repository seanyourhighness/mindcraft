package net.clankerjockey.core.agent;

import net.clankerjockey.core.events.EventLog;
import net.clankerjockey.core.memory.MemoryLedger;
import net.clankerjockey.core.memory.PromptAssembler;
import net.clankerjockey.core.tasks.TaskManager;
import net.clankerjockey.core.world.AgentWorld;
import net.clankerjockey.core.world.ContainerStore;
import net.clankerjockey.core.world.PlaceMemory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything the agent loop and its tools know about the current turn:
 * who is talking, what world/body the companion has, memory, recent history
 * and cancellation state.
 *
 * <p>One instance per turn. {@link #requestCancel()} may be called from any
 * thread (e.g. the game thread when the player issues a stop) and is observed
 * by the loop and executor between iterations.</p>
 */
public final class AgentContext {

    private final String playerId;
    private final String worldId;
    private final boolean owner;
    private final AgentWorld world;
    private final MemoryLedger ledger;
    private final PlaceMemory places;
    private final ContainerStore containers;
    private final TaskManager tasks;
    private final EventLog events;
    private final List<PromptAssembler.Turn> history;
    private final Map<String, Object> attributes;
    private final AgentLogger logger;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public AgentContext(String playerId, String worldId, boolean owner,
                        AgentWorld world, MemoryLedger ledger,
                        PlaceMemory places,
                        ContainerStore containers,
                        TaskManager tasks,
                        EventLog events,
                        List<PromptAssembler.Turn> history,
                        Map<String, Object> attributes, AgentLogger logger) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.worldId = worldId == null ? "unknown" : worldId;
        this.owner = owner;
        this.world = Objects.requireNonNull(world, "world");
        this.ledger = ledger;
        this.places = places;
        this.containers = containers;
        this.tasks = tasks;
        this.events = events;
        this.history = history == null ? List.of() : List.copyOf(history);
        this.attributes = attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
        this.logger = logger == null ? AgentLogger.NOOP : logger;
    }

    public String playerId() {
        return playerId;
    }

    public String worldId() {
        return worldId;
    }

    /** True when the speaker is the mod owner (full authorization). */
    public boolean isOwner() {
        return owner;
    }

    public AgentWorld world() {
        return world;
    }

    public MemoryLedger ledger() {
        return ledger;
    }

    public PlaceMemory places() {
        return places;
    }

    public ContainerStore containers() {
        return containers;
    }

    public TaskManager tasks() {
        return tasks;
    }

    public EventLog events() {
        return events;
    }

    public List<PromptAssembler.Turn> history() {
        return history;
    }

    /** Extensible per-turn state for tools (e.g. cached observations). */
    public Map<String, Object> attributes() {
        return attributes;
    }

    public AgentLogger logger() {
        return logger;
    }

    /** Ask the loop to stop as soon as possible (thread-safe). */
    public void requestCancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public static Builder builder(String playerId, AgentWorld world) {
        return new Builder(playerId, world);
    }

    /** Fluent builder for a per-turn context. */
    public static final class Builder {
        private final String playerId;
        private final AgentWorld world;
        private String worldId = "unknown";
        private boolean owner;
        private MemoryLedger ledger;
        private PlaceMemory places;
        private ContainerStore containers;
        private TaskManager tasks;
        private EventLog events;
        private List<PromptAssembler.Turn> history = List.of();
        private Map<String, Object> attributes = new LinkedHashMap<>();
        private AgentLogger logger = AgentLogger.NOOP;

        private Builder(String playerId, AgentWorld world) {
            this.playerId = playerId;
            this.world = world;
        }

        public Builder worldId(String worldId) {
            this.worldId = worldId;
            return this;
        }

        public Builder owner(boolean owner) {
            this.owner = owner;
            return this;
        }

        public Builder ledger(MemoryLedger ledger) {
            this.ledger = ledger;
            return this;
        }

        public Builder places(PlaceMemory places) {
            this.places = places;
            return this;
        }

        public Builder containers(ContainerStore containers) {
            this.containers = containers;
            return this;
        }

        public Builder tasks(TaskManager tasks) {
            this.tasks = tasks;
            return this;
        }

        public Builder events(EventLog events) {
            this.events = events;
            return this;
        }

        public Builder history(List<PromptAssembler.Turn> history) {
            this.history = history == null ? List.of() : new ArrayList<>(history);
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            this.attributes = attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
            return this;
        }

        public Builder logger(AgentLogger logger) {
            this.logger = logger;
            return this;
        }

        public AgentContext build() {
            return new AgentContext(playerId, worldId, owner, world, ledger, places, containers,
                    tasks, events, history, attributes, logger);
        }
    }
}
