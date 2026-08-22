package net.mindcraft.core.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the final user-visible prompt from a system prompt, the memory
 * ledger and recent raw history. Layout:
 *
 * <pre>
 * [system prompt]            — persona + world rules (kept short, ~350 tok cap)
 * [ledger block]             — backstory summary + durable facts
 * [last K raw turns]         — verbatim recent dialogue window
 * [current player message]   — appended last
 * </pre>
 *
 * The model never sees turns older than the window; their gist lives in the
 * ledger. All methods are static and side-effect free.
 */
public final class PromptAssembler {

    /** Default raw-turn window injected before the current message. */
    public static final int DEFAULT_WINDOW = 8;

    private PromptAssembler() {}

    /** One past exchange. */
    public record Turn(String user, String assistant) {}

    /**
     * Build the full prompt.
     *
     * @param system      system prompt (persona/world rules); must be non-blank
     * @param ledgerBlock rendered memory from {@link MemoryLedger#render()}; may be null/empty
     * @param history     full raw history, oldest first; only the tail is used
     * @param window      how many trailing turns to include verbatim
     * @param userMessage the new player message
     */
    public static String build(String system, String ledgerBlock,
                               List<Turn> history, int window, String userMessage) {
        if (system == null || system.isBlank()) {
            throw new IllegalArgumentException("system prompt must not be blank");
        }
        StringBuilder sb = new StringBuilder(system.strip());
        sb.append("\n\n");
        if (ledgerBlock != null && !ledgerBlock.isBlank()) {
            sb.append(ledgerBlock.strip()).append("\n\n");
        }
        List<Turn> tail = tail(history, window);
        if (!tail.isEmpty()) {
            sb.append("Recent conversation:\n");
            for (Turn t : tail) {
                sb.append("Player: ").append(t.user().strip()).append('\n');
                sb.append("You: ").append(t.assistant().strip()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("Player: ").append(userMessage == null ? "" : userMessage.strip()).append('\n');
        sb.append("You:");
        return sb.toString();
    }

    /** Convenience overload with the default window. */
    public static String build(String system, String ledgerBlock,
                               List<Turn> history, String userMessage) {
        return build(system, ledgerBlock, history, DEFAULT_WINDOW, userMessage);
    }

    private static List<Turn> tail(List<Turn> history, int window) {
        if (history == null || history.isEmpty() || window <= 0) {
            return List.of();
        }
        int from = Math.max(0, history.size() - window);
        return new ArrayList<>(history.subList(from, history.size()));
    }
}
