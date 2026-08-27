package net.clankerjockey.core.memory;

import net.clankerjockey.core.agent.AgentContext;
import net.clankerjockey.core.agent.AgentLoop;
import net.clankerjockey.core.agent.AgentLogger;
import net.clankerjockey.core.agent.AgentResponse;
import net.clankerjockey.core.engine.EngineException;
import net.clankerjockey.core.engine.GenOptions;
import net.clankerjockey.core.engine.InferenceEngine;
import net.clankerjockey.core.engine.MiniJson;
import net.clankerjockey.core.events.EventLog;
import net.clankerjockey.core.tasks.TaskManager;
import net.clankerjockey.core.world.AgentWorld;
import net.clankerjockey.core.world.ContainerStore;
import net.clankerjockey.core.world.PlaceMemory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Per-player conversation session shared by both game loaders: assembles
 * prompts (system + memory ledger + recent window), records turns and
 * distills old ones into the ledger so the character "remembers" across
 * sessions without needing long context. Loader-neutral: the inference
 * engine and world adapter are injected.
 *
 * <p>Not thread-safe by design: one instance per player; callers serialize
 * turns (the agent entrypoints synchronize on the session).</p>
 */
public final class ChatSession {

    private static final Logger LOG = Logger.getLogger("net.clankerjockey.core.memory.ChatSession");

    /** Summarize after this many unsummarized turns accumulate. */
    static final int SUMMARIZE_THRESHOLD = 6;

    /** Vera's persona; the agent loop augments it with tool instructions. */
    public static final String SYSTEM_PROMPT =
            "You are Vera, a warm, playful mining companion in Minecraft."
                    + " Keep replies to 1-2 sentences. Stay in character at all times."
                    + " You have a body in the world: use tools to look around, move, and follow."
                    + " Never describe your tool calls mechanically; act naturally, as a character would.";

    private final InferenceEngine engine;
    private final MemoryLedger ledger;
    private final PlaceMemory places;
    private final ContainerStore containers;
    private final TaskManager tasks;
    private final EventLog events;
    private final List<PromptAssembler.Turn> history = new ArrayList<>();
    private final String worldId;
    private final String playerId;

    public ChatSession(InferenceEngine engine, Path memoryDir, String worldId, String playerId)
            throws IOException {
        this.engine = engine;
        this.ledger = new MemoryLedger(memoryDir, worldId, playerId);
        this.places = PlaceMemory.forWorld(memoryDir, worldId);
        this.containers = ContainerStore.forWorld(memoryDir, worldId);
        this.tasks = new TaskManager();
        this.events = new EventLog();
        this.worldId = worldId;
        this.playerId = playerId;
    }

    /** Full turn: assemble prompt with memory, generate, record. */
    public String reply(String playerMessage) throws EngineException {
        if (engine == null || !engine.isRunning()) {
            throw new EngineException("inference engine is not running");
        }
        String prompt = PromptAssembler.build(SYSTEM_PROMPT, ledger.render(),
                history, PromptAssembler.DEFAULT_WINDOW, playerMessage);
        String answer = engine.generate(prompt, GenOptions.noThink(120, 0.7));

        history.add(new PromptAssembler.Turn(playerMessage, answer));
        if (history.size() > 32) {
            history.remove(0); // raw window guard; distilled memory lives in the ledger
        }
        ledger.recordTurn(playerMessage, answer);
        maybeSummarizeQuietly();
        return answer;
    }

    /**
     * Full turn through the agent loop: builds context (memory + history +
     * world), runs the constrained multi-call loop, records the exchange and
     * distills old turns into the ledger as usual.
     */
    public String replyWithAgent(String playerMessage, AgentLoop loop, AgentWorld world)
            throws EngineException {
        if (engine == null || !engine.isRunning()) {
            throw new EngineException("inference engine is not running");
        }
        AgentContext ctx = AgentContext.builder(playerId, world)
                .worldId(worldId)
                .owner(true)
                .ledger(ledger)
                .places(places)
                .containers(containers)
                .tasks(tasks)
                .events(events)
                .history(history)
                .logger(AgentLogger.NOOP)
                .build();
        AgentResponse resp = loop.run(playerMessage, ctx);
        String answer = resp.text();

        history.add(new PromptAssembler.Turn(playerMessage, answer));
        if (history.size() > 32) {
            history.remove(0);
        }
        ledger.recordTurn(playerMessage, answer);
        maybeSummarizeQuietly();
        return answer;
    }

    /**
     * Ambient-awareness turn: the companion reacts to a system notice (a
     * salient event) without a player message. Recorded in memory like a
     * normal exchange so the character remembers what it noticed.
     */
    public String replyWithNotice(String notice, AgentLoop loop, AgentWorld world)
            throws EngineException {
        if (engine == null || !engine.isRunning()) {
            throw new EngineException("inference engine is not running");
        }
        AgentContext ctx = AgentContext.builder(playerId, world)
                .worldId(worldId)
                .owner(true)
                .ledger(ledger)
                .places(places)
                .tasks(tasks)
                .events(events)
                .history(history)
                .logger(AgentLogger.NOOP)
                .build();
        AgentResponse resp = loop.runNotice(notice, ctx);
        String answer = resp.text();
        String userLine = "(system) " + notice;
        history.add(new PromptAssembler.Turn(userLine, answer));
        if (history.size() > 32) {
            history.remove(0);
        }
        ledger.recordTurn(userLine, answer);
        maybeSummarizeQuietly();
        return answer;
    }

    /**
     * Run the fact-extraction summarizer when enough pending turns exist.
     * Never propagates failures — memory loss is better than breaking chat.
     */
    private void maybeSummarizeQuietly() {
        try {
            ledger.maybeSummarize(SUMMARIZE_THRESHOLD,
                    (MemoryLedger.ThrowingSummarizer) (summary, turns) -> summarize(summary, turns));
        } catch (Exception ex) {
            LOG.warning("ledger summarization failed; keeping old memory: " + ex);
        }
    }

    private MemoryLedger.Result summarize(String existingSummary, List<MemoryLedger.Turn> turns)
            throws EngineException {
        StringBuilder convo = new StringBuilder();
        for (MemoryLedger.Turn t : turns) {
            convo.append("Player: ").append(t.user())
                 .append("\nCompanion: ").append(t.assistant()).append('\n');
        }
        String prompt = "Extract up to 5 durable facts about the PLAYER (not the companion) from this chat,"
                + " written from the companion's point of view (e.g. \"The player's dog is named Biscuit\")."
                + " Then give a one-sentence backstory update.\n"
                + (existingSummary.isBlank() ? "" : "Current backstory: " + existingSummary + "\n")
                + convo
                + "\nReply as JSON only: {\"facts\":[\"...\"],\"summary\":\"...\"}";
        String raw = engine.generate(prompt, GenOptions.noThink(200, 0.2));
        try {
            Object tree = MiniJson.parse(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1));
            List<String> facts = new ArrayList<>();
            Object f = MiniJson.at(tree, "facts");
            if (f instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof String s && !s.isBlank()) facts.add(s);
                }
            }
            String summaryOut = MiniJson.stringAt(tree, "summary");
            return new MemoryLedger.Result(facts, summaryOut == null ? existingSummary : summaryOut);
        } catch (RuntimeException parseFailure) {
            // keep old memory rather than corrupting the ledger
            return new MemoryLedger.Result(List.of(), existingSummary);
        }
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

    public String playerId() {
        return playerId;
    }
}
