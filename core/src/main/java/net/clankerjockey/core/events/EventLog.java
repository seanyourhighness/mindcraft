package net.clankerjockey.core.events;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Bounded log of recent semantic events the LLM should know about (reflex
 * summaries, notable discoveries, task outcomes...). Rendered into the agent
 * prompt so the companion stays aware without seeing raw game ticks.
 */
public final class EventLog {

    private final int max;
    private final Deque<SemanticEvent> events = new ArrayDeque<>();

    public EventLog() {
        this(8);
    }

    public EventLog(int max) {
        this.max = Math.max(1, max);
    }

    public synchronized void add(SemanticEvent event) {
        if (event == null) return;
        events.addLast(event);
        while (events.size() > max) {
            events.removeFirst();
        }
    }

    public synchronized void clear() {
        events.clear();
    }

    public synchronized boolean isEmpty() {
        return events.isEmpty();
    }

    /** Prompt block, or empty string when nothing notable happened. */
    public synchronized String render() {
        if (events.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Recent events:\n");
        for (SemanticEvent e : events) {
            sb.append("- ").append(e.renderLine()).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
