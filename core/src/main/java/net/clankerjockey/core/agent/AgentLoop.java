package net.clankerjockey.core.agent;

import net.clankerjockey.core.engine.EngineException;
import net.clankerjockey.core.engine.GenOptions;
import net.clankerjockey.core.engine.InferenceBackend;
import net.clankerjockey.core.engine.MiniJson;
import net.clankerjockey.core.engine.ToolGrammar;
import net.clankerjockey.core.memory.PromptAssembler;
import net.clankerjockey.core.tools.ParamSpec;
import net.clankerjockey.core.tools.ToolCall;
import net.clankerjockey.core.tools.ToolDefinition;
import net.clankerjockey.core.tools.ToolRegistry;
import net.clankerjockey.core.tools.ToolResult;
import net.clankerjockey.core.tools.ToolExecutor;
import net.clankerjockey.core.world.SelfState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reusable multi-call agent loop:
 *
 * <pre>
 * trigger → build context → LLM call → structured tool call?
 *   yes → validate → execute → append result → LLM call again
 *   no  → final natural-language response
 * </pre>
 *
 * Multiple sequential tool calls happen without any new player message. The
 * loop is bounded by {@link AgentLoopConfig}: max tool calls, max inference
 * iterations, repeated-identical-call detection, consecutive-failure cap,
 * per-call timeout and whole-loop timeout. {@code context.requestCancel()}
 * may be called from any thread to interrupt.
 */
public final class AgentLoop {

    private final InferenceBackend backend;
    private final ToolRegistry registry;
    private final ToolExecutor executor;
    private final AgentLoopConfig config;
    private final String systemPrompt;

    public AgentLoop(InferenceBackend backend, ToolRegistry registry,
                     ToolExecutor executor, AgentLoopConfig config, String systemPrompt) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.config = config == null ? AgentLoopConfig.defaults() : config;
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
        if (!registry.contains(RespondTool.NAME)) {
            registry.register(new RespondTool());
        }
    }

    /**
     * Run one turn: the player message is turned into a constrained LLM
     * conversation that may chain tool calls until the model responds.
     *
     * @throws EngineException when the inference backend fails
     */
    public AgentResponse run(String playerMessage, AgentContext context) throws EngineException {
        return runLoop(buildInitialPrompt(playerMessage, context), context);
    }

    /**
     * Run one turn triggered by a system notice (ambient awareness): the model
     * reacts to a salient event (e.g. a creeper near the player) without any
     * player message, using tools if needed and replying in character.
     */
    public AgentResponse runNotice(String notice, AgentContext context) throws EngineException {
        if (notice == null || notice.isBlank()) {
            notice = "Something noteworthy happened nearby.";
        }
        return runLoop(buildNoticePrompt(notice, context), context);
    }

    private AgentResponse runLoop(String promptText, AgentContext context) throws EngineException {
        long start = System.nanoTime();
        List<ToolTrace> traces = new ArrayList<>();
        List<ToolCall> rawCalls = new ArrayList<>();
        StringBuilder prompt = new StringBuilder(promptText);

        int iterations = 0;
        String finalText = null;
        boolean interrupted = false;
        boolean limitExceeded = false;
        int repeatedStreak = 0;
        int failureStreak = 0;
        String lastSignature = null;
        String lastFailureMessage = "";
        boolean nudgedRepeats = false;

        while (iterations < config.maxInferenceIterations()) {
            if (context.isCancelled()) {
                interrupted = true;
                break;
            }
            if (elapsedMs(start) > config.loopTimeoutMs()) {
                limitExceeded = true;
                context.logger().log("Loop timeout exceeded; stopping.");
                break;
            }

            GenOptions opts = GenOptions.noThink(config.maxTokens(), config.temperature())
                    .withGrammar(ToolGrammar.generate(registry.definitions()));
            String raw = backend.generate(prompt.toString(), opts);
            iterations++;
            context.logger().log("Iteration " + iterations + " LLM -> " + truncate(raw, 180));

            ToolCall call = ToolCall.tryParse(parseLenient(raw));
            if (call == null) {
                // Under a strict grammar this is rare; treat any non-JSON text
                // as an (unexpected) direct answer rather than dropping it.
                finalText = cleanText(raw);
                if (finalText.isBlank()) {
                    finalText = defaultFallback();
                }
                break;
            }
            rawCalls.add(call);

            if (RespondTool.NAME.equals(call.name())) {
                Object t = call.arguments().get("text");
                finalText = cleanText(t instanceof String s ? s : "");
                if (finalText.isBlank()) {
                    finalText = defaultFallback();
                }
                break;
            }

            String signature = call.signature();
            repeatedStreak = signature.equals(lastSignature) ? repeatedStreak + 1 : 1;
            lastSignature = signature;
            if (repeatedStreak > config.maxRepeatedIdenticalCalls()) {
                if (!nudgedRepeats) {
                    // One bounded nudge: the model may just need to be told the
                    // action is done and it should speak now. No extra execution
                    // happens; if it repeats again we stop.
                    nudgedRepeats = true;
                    context.logger().log("Repeated identical call " + signature
                            + " (x" + repeatedStreak + "); nudging model to respond.");
                    prompt.append("\n\nSYSTEM: You already performed ").append(call.name())
                            .append(" and it is done. Do NOT call it again. "
                                    + "Speak to the player now with {\"tool\":\"respond\",...}.\n\nYou:");
                    continue;
                }
                limitExceeded = true;
                context.logger().log("Repeated identical call " + signature + " (x" + repeatedStreak + "); stopping.");
                finalText = "Hmm, I keep trying the same thing and it isn't working. Let me stop here and you can tell me again.";
                break;
            }

            ToolResult result = executor.execute(call, context);
            traces.add(new ToolTrace(call, result));
            context.logger().log("Iteration " + iterations + " tool " + call.name()
                    + " -> " + ToolResult.statusName(result.status()));

            if (context.isCancelled() || result.status() == ToolResult.Status.CANCELLED) {
                interrupted = true;
                break;
            }
            if (result.status() == ToolResult.Status.INTERRUPTED) {
                interrupted = true;
                finalText = "I got interrupted mid-action. What would you like me to do?";
                break;
            }

            boolean failed = result.status() == ToolResult.Status.FAILED
                    || result.status() == ToolResult.Status.BLOCKED
                    || result.status() == ToolResult.Status.DENIED
                    || result.status() == ToolResult.Status.TIMED_OUT;
            if (failed) {
                failureStreak++;
                lastFailureMessage = result.message();
            } else {
                failureStreak = 0;
            }
            if (failureStreak >= config.maxConsecutiveFailures()) {
                limitExceeded = true;
                context.logger().log("Consecutive tool failures (" + failureStreak + "); stopping.");
                finalText = "I'm having trouble pulling that off right now (" + shortReason(lastFailureMessage)
                        + "). Want to try something else?";
                break;
            }
            if (traces.size() >= config.maxToolCalls()) {
                limitExceeded = true;
                context.logger().log("Max tool calls (" + config.maxToolCalls() + ") reached; stopping.");
                finalText = "I've used up my actions for this turn. That's where I got to — tell me if you want me to keep going.";
                break;
            }

            prompt.append("\n\nTOOL RESULT\n")
                    .append(cap(compactResult(result), config.maxToolResultChars()))
                    .append("\n\nYou:");
        }

        if (finalText == null) {
            finalText = defaultFallback();
            interrupted = true;
        }
        context.logger().log("Turn complete in " + iterations + " iteration(s); response: " + truncate(finalText, 140));
        return new AgentResponse(finalText, traces, iterations, interrupted, limitExceeded, rawCalls);
    }

    // --- prompt construction ------------------------------------------------

    private String buildInitialPrompt(String playerMessage, AgentContext context) {
        return buildPrompt(playerMessage, null, context);
    }

    private String buildNoticePrompt(String notice, AgentContext context) {
        return buildPrompt(null, notice, context);
    }

    private String buildPrompt(String playerMessage, String notice, AgentContext context) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append(systemPrompt.strip()).append("\n\n");
        sb.append("You act through tools. Respond with exactly one JSON object, nothing else:\n");
        sb.append("{\"tool\": \"<tool name>\", \"arguments\": {<that tool's arguments>}}\n");
        sb.append("Use \"respond\" with {\"text\": \"...\"} as your final message to the player.\n");
        sb.append("Never explain tool calls to the player; just act and speak in character.\n\n");
        sb.append("Available tools:\n");
        for (ToolDefinition def : registry.definitions()) {
            sb.append("- ").append(def.name()).append(": ").append(def.description());
            List<ParamSpec> params = def.parameters();
            if (!params.isEmpty()) {
                sb.append(" (args: ");
                for (int i = 0; i < params.size(); i++) {
                    if (i > 0) sb.append(", ");
                    ParamSpec p = params.get(i);
                    sb.append(p.name()).append(": ").append(p.type().name().toLowerCase());
                    if (!p.required()) sb.append(" [optional]");
                }
                sb.append(')');
            }
            sb.append('\n');
        }
        sb.append("\nExamples:\n");
        sb.append("Player: What are you carrying?\n");
        sb.append("You: {\"tool\":\"get_inventory\",\"arguments\":{}}\n");
        sb.append("TOOL RESULT: {\"status\":\"success\",\"message\":\"Carrying nothing.\",\"items\":[]}\n");
        sb.append("You: {\"tool\":\"respond\",\"arguments\":{\"text\":\"Not much, I'm afraid.\"}}\n");
        sb.append("Player: Stop following me and go back home.\n");
        sb.append("You: {\"tool\":\"stop_following\",\"arguments\":{}}\n");
        sb.append("TOOL RESULT: {\"status\":\"success\",\"message\":\"Stopped following.\"}\n");
        sb.append("You: {\"tool\":\"go_to_remembered_place\",\"arguments\":{\"name\":\"home\"}}\n");
        sb.append("TOOL RESULT: {\"status\":\"success\",\"message\":\"Started walking to the target.\"}\n");
        sb.append("You: {\"tool\":\"respond\",\"arguments\":{\"text\":\"All set, heading home!\"}}\n");
        sb.append("Player: Follow me!\n");
        sb.append("You: {\"tool\":\"follow_player\",\"arguments\":{\"player\":\"Sean\",\"distance\":4}}\n");
        sb.append("TOOL RESULT: {\"status\":\"success\",\"message\":\"Now following Sean.\",\"following\":\"Sean\",\"distance\":4}\n");
        sb.append("You: {\"tool\":\"respond\",\"arguments\":{\"text\":\"Right behind you!\"}}\n");
        sb.append("IMPORTANT: After a tool call succeeds, do NOT call it again. "
                + "Complete ALL parts of the player's request, then "
                + "use {\"tool\":\"respond\",...} to speak to the player.\n");

        if (context.events() != null) {
            String events = context.events().render();
            if (!events.isBlank()) {
                sb.append('\n').append(events).append('\n');
            }
        }

        if (context.ledger() != null) {
            String block = context.ledger().render();
            if (!block.isBlank()) {
                sb.append('\n').append(block).append('\n');
            }
        }

        List<PromptAssembler.Turn> history = context.history();
        if (!history.isEmpty()) {
            sb.append("\nRecent conversation:\n");
            for (PromptAssembler.Turn t : history) {
                sb.append("Player: ").append(t.user().strip()).append('\n');
                sb.append("You: ").append(t.assistant().strip()).append('\n');
            }
        }

        try {
            SelfState self = context.world().selfState();
            sb.append("\nWorld context: you are talking to ").append(context.playerId())
                    .append("; position (").append(round2(self.x())).append(", ")
                    .append(round2(self.y())).append(", ").append(round2(self.z())).append(')')
                    .append(" in ").append(self.dimension())
                    .append(" / ").append(self.biome())
                    .append("; time ").append(self.timeOfDay())
                    .append("; weather ").append(self.weather());
            if (self.followingPlayer() != null && !self.followingPlayer().isBlank()) {
                sb.append("; currently following ").append(self.followingPlayer());
            }
            sb.append(".\n");
        } catch (RuntimeException e) {
            sb.append("\nWorld context: unavailable.\n");
        }

        if (notice != null && !notice.isBlank()) {
            sb.append("\nSystem notice: ").append(notice.strip()).append('\n');
        } else {
            sb.append("\nPlayer: ").append(playerMessage == null ? "" : playerMessage.strip()).append('\n');
        }
        sb.append("You:");
        return sb.toString();
    }

    private static String compactResult(ToolResult r) {
        String rendered = r.render();
        return rendered.length() <= 500 ? rendered : rendered.substring(0, 500) + "...";
    }

    private static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // --- helpers -------------------------------------------------------------

    private static Object parseLenient(String raw) {
        if (raw == null) return null;
        String s = raw.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) s = s.substring(nl + 1);
            int end = s.lastIndexOf("```");
            if (end >= 0) s = s.substring(0, end);
        }
        int first = s.indexOf('{');
        int last = s.lastIndexOf('}');
        if (first < 0 || last <= first) {
            return null;
        }
        try {
            return MiniJson.parse(s.substring(first, last + 1));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String cleanText(String s) {
        if (s == null) return "";
        String out = s.strip();
        if (out.startsWith("```")) {
            int nl = out.indexOf('\n');
            if (nl >= 0) out = out.substring(nl + 1);
            int end = out.lastIndexOf("```");
            if (end >= 0) out = out.substring(0, end);
            out = out.strip();
        }
        if (out.length() >= 2 && out.startsWith("\"") && out.endsWith("\"")) {
            out = out.substring(1, out.length() - 1);
        }
        return out.replace('\n', ' ').replace('\r', ' ').strip();
    }

    private static String defaultFallback() {
        return "Sorry, my thoughts got a bit tangled there. Could you say that again?";
    }

    private static String shortReason(String message) {
        if (message == null || message.isBlank()) return "no clear reason";
        String m = message.strip();
        return m.length() <= 80 ? m : m.substring(0, 80) + "...";
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String round2(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }
}
