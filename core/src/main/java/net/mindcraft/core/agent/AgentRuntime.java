package net.mindcraft.core.agent;

import net.mindcraft.core.engine.MiniJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.BiConsumer;

/**
 * The MindCraft agent: a tiny observe → reason → act loop.
 *
 * <p>Sensors (Forge event handlers, tick polls) call {@link #observe(Signal)}.
 * When a signal matches a {@link Watch}, the runtime builds a short prompt and
 * hands it to the {@link Reasoner} (the local LLM). The LLM's reply is either:
 * <ul>
 *   <li>a JSON tool call — {@code {"tool":"give_item","args":{...}}} —
 *       delivered to {@link #onToolCall}, or</li>
 *   <li>plain text — delivered to {@link #onSpeech} (TTS speaks it).</li>
 * </ul>
 *
 * <p>Per-watch cooldowns and max-fires keep it lightweight: a watch that
 * keeps matching (e.g. "still in the crimson forest") only pings the LLM at
 * most once per {@code cooldownMs}. When no watch matches, {@link #observe}
 * returns immediately — zero LLM cost.
 *
 * <p>JDK-only, no Minecraft imports: fully unit-testable.
 */
public final class AgentRuntime {

    /** LLM call: prompt in, raw text out. */
    @FunctionalInterface
    public interface Reasoner {
        String reason(String prompt) throws Exception;
    }

    /** In-game action sink: tool name + JSON args (already parsed). */
    @FunctionalInterface
    public interface ToolSink {
        void onTool(String name, Map<String, Object> args);
    }

    /** Spoken-line sink (TTS). */
    @FunctionalInterface
    public interface SpeechSink {
        void onSpeech(String line);
    }

    private final Reasoner reasoner;
    private final List<Watch> watches = new ArrayList<>();
    private final ConcurrentHashMap<Watch, AtomicLong> lastFired = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Watch, AtomicInteger> fireCounts = new ConcurrentHashMap<>();
    private ToolSink toolSink = (name, args) -> {
    };
    private SpeechSink speechSink = line -> {
    };
    private volatile String persona = "You are Vera, a warm, playful mining companion in Minecraft.";
    /** When set, LLM reasoning runs here instead of the calling thread. */
    private java.util.concurrent.ExecutorService reasonExecutor;

    public AgentRuntime(Reasoner reasoner) {
        this.reasoner = reasoner;
    }

    /**
     * Run LLM reasoning on this executor instead of the calling thread.
     * {@link #observe} stays fast (matching only); the blocking generation
     * happens off-thread. Call before {@code observe} is used.
     */
    public AgentRuntime reasonOn(java.util.concurrent.ExecutorService executor) {
        this.reasonExecutor = executor;
        return this;
    }

    public AgentRuntime watch(Watch w) {
        watches.add(w);
        return this;
    }

    public AgentRuntime persona(String persona) {
        this.persona = persona;
        return this;
    }

    public AgentRuntime toolSink(ToolSink sink) {
        this.toolSink = sink;
        return this;
    }

    public AgentRuntime speechSink(SpeechSink sink) {
        this.speechSink = sink;
        return this;
    }

    /**
     * Feed an observation. Returns the watch that fired, or {@code null} when
     * nothing matched (or a cooldown suppressed it). When a watch fires, the
     * LLM is called and its reply routed to the tool/speech sinks.
     */
    public Watch observe(Signal signal) {
        Watch fired = null;
        long now = System.currentTimeMillis();
        for (Watch w : watches) {
            if (!w.matches(signal)) {
                continue;
            }
            if (w.maxFires > 0 && fireCounts.computeIfAbsent(w, k -> new AtomicInteger()).get() >= w.maxFires) {
                continue;
            }
            AtomicLong last = lastFired.computeIfAbsent(w, k -> new AtomicLong(0));
            long prev = last.get();
            if (w.cooldownMs > 0 && now - prev < w.cooldownMs) {
                continue;
            }
            if (!last.compareAndSet(prev, now)) {
                continue; // another thread won the race
            }
            fireCounts.computeIfAbsent(w, k -> new AtomicInteger()).incrementAndGet();
            fired = w;
            if (reasonExecutor != null) {
                reasonExecutor.submit(() -> reason(w, signal));
            } else {
                reason(w, signal);
            }
        }
        return fired;
    }

    private void reason(Watch w, Signal signal) {
        String prompt = buildPrompt(w, signal);
        String raw;
        try {
            raw = reasoner.reason(prompt);
        } catch (Exception e) {
            return; // LLM unavailable: the world keeps running, agent stays quiet
        }
        if (raw == null || raw.isBlank()) {
            return;
        }
        String json = extractJson(raw);
        if (json != null) {
            try {
                Object tree = MiniJson.parse(json);
                if (tree instanceof Map<?, ?> m) {
                    Object tool = m.get("tool");
                    if (tool instanceof String t && !t.isBlank()) {
                        Object args = m.get("args");
                        Map<String, Object> argMap =
                                args instanceof Map<?, ?> am ? castArgs(am) : Map.of();
                        toolSink.onTool(t, argMap);
                        return;
                    }
                }
            } catch (RuntimeException ignored) {
                // fall through to speech
            }
        }
        speechSink.onSpeech(raw.trim());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castArgs(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    /** First balanced {...} block in the reply, or null. */
    static String extractJson(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{' -> depth++;
                case '}' -> {
                    depth--;
                    if (depth == 0) {
                        return text.substring(start, i + 1);
                    }
                }
                default -> {
                }
            }
        }
        return null;
    }

    private String buildPrompt(Watch w, Signal signal) {
        StringBuilder sb = new StringBuilder();
        sb.append(persona).append('\n');
        sb.append("You can reply with a short spoken line, or a JSON tool call: "
                + "{\"tool\":\"<name>\",\"args\":{...}}. Available tools: "
                + "give_item, teleport, set_block, spawn_mob, follow, stop.\n");
        sb.append("Observation: ").append(describe(signal));
        if (w.note != null && !w.note.isBlank()) {
            sb.append(" Context: ").append(w.note);
        }
        sb.append('\n').append("Respond in character, one short sentence, or a tool call.");
        return sb.toString();
    }

    private static String describe(Signal s) {
        return switch (s.kind) {
            case MOB -> "a " + s.subject + " spawned nearby";
            case BIOME -> "the player entered the " + s.subject + " biome";
            case STRUCTURE -> "a " + s.subject + " structure is nearby";
            case ITEM_USE -> "the player used a " + s.subject;
            case CHAT -> "chat message: \"" + s.subject + "\"";
            case BLOCK_BREAK -> "the player mined a " + s.subject;
        };
    }
}
