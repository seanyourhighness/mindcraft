package net.mindcraft.mod;

import net.mindcraft.core.engine.EngineException;
import net.mindcraft.core.engine.GenOptions;
import net.mindcraft.core.engine.InferenceEngine;
import net.mindcraft.core.engine.MiniJson;
import net.mindcraft.core.memory.MemoryLedger;
import net.mindcraft.core.memory.PromptAssembler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-player chat session: assembles prompts (system + memory ledger + recent
 * window), records turns and distills old ones into the ledger so the
 * character "remembers" across sessions without needing long context.
 *
 * <p>Not thread-safe by design: one instance per player, used on the client
 * thread.</p>
 */
public final class ChatSession {

    /** Summarize after this many unsummarized turns accumulate. */
    static final int SUMMARIZE_THRESHOLD = 6;

    private static final String SYSTEM_PROMPT =
            "You are Vera, a warm, playful mining companion in Minecraft."
                    + " Keep replies to 1-2 sentences. Stay in character at all times.";

    private final MemoryLedger ledger;
    private final List<PromptAssembler.Turn> history = new ArrayList<>();

    public ChatSession(Path memoryDir, String worldId, String playerId) throws IOException {
        this.ledger = new MemoryLedger(memoryDir, worldId, playerId);
    }

    /** Full turn: assemble prompt with memory, generate, record. */
    public String reply(String playerMessage) throws EngineException {
        InferenceEngine e = MindCraftMod.engine();
        if (e == null || !e.isRunning()) {
            throw new EngineException("inference engine is not running");
        }
        String prompt = PromptAssembler.build(SYSTEM_PROMPT, ledger.render(),
                history, PromptAssembler.DEFAULT_WINDOW, playerMessage);
        String answer = e.generate(prompt, GenOptions.noThink(120, 0.7));

        history.add(new PromptAssembler.Turn(playerMessage, answer));
        if (history.size() > 32) {
            history.remove(0); // raw window guard; distilled memory lives in the ledger
        }
        ledger.recordTurn(playerMessage, answer);
        maybeSummarizeQuietly(e);
        return answer;
    }

    /**
     * Run the fact-extraction summarizer when enough pending turns exist.
     * Never propagates failures — memory loss is better than breaking chat.
     */
    private void maybeSummarizeQuietly(InferenceEngine e) {
        try {
            ledger.maybeSummarize(SUMMARIZE_THRESHOLD,
                    (MemoryLedger.ThrowingSummarizer) (summary, turns) -> summarize(e, summary, turns));
        } catch (Exception ex) {
            MindCraftMod.LOGGER.warn("[mindcraft] ledger summarization failed; keeping old memory", ex);
        }
    }

    private static MemoryLedger.Result summarize(InferenceEngine engine, String existingSummary,
                                                 List<MemoryLedger.Turn> turns) throws EngineException {
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
}
