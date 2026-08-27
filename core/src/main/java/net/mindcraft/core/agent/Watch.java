package net.mindcraft.core.agent;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A condition the agent watches for: "when a signal of this kind arrives
 * (and its subject matches), observe it".
 *
 * <p>The watch itself does nothing — matching a watch makes the
 * {@link AgentRuntime} send the observation to the LLM, which decides the
 * reaction (a spoken line or a tool call). The watch only shapes the prompt
 * via its {@code note}.
 *
 * <p>Matching rules:
 * <ul>
 *   <li>{@code kind} must equal the signal's kind.</li>
 *   <li>{@code subjects} empty = any subject. Otherwise the signal's subject
 *       must be in the set (registry IDs, case-insensitive).</li>
 *   <li>{@code contains} (CHAT): the signal's subject must contain the
 *       keyword, case-insensitive.</li>
 * </ul>
 */
public final class Watch {

    public final Signal.Kind kind;
    public final Set<String> subjects;
    public final String contains;
    /** Extra context appended to the prompt when this watch fires. */
    public final String note;
    /** Minimum gap between firings (ms). 0 = no cooldown. */
    public final long cooldownMs;
    /** Fire at most N times total (0 = unlimited). */
    public final int maxFires;

    private Watch(Builder b) {
        this.kind = b.kind;
        this.subjects = b.subjects;
        this.contains = b.contains;
        this.note = b.note;
        this.cooldownMs = b.cooldownMs;
        this.maxFires = b.maxFires;
    }

    public boolean matches(Signal s) {
        if (s.kind != kind) {
            return false;
        }
        if (contains != null) {
            return s.subject != null && s.subject.toLowerCase(Locale.ROOT).contains(contains);
        }
        if (subjects.isEmpty()) {
            return true;
        }
        return s.subject != null && subjects.contains(s.subject.toLowerCase(Locale.ROOT));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Signal.Kind kind;
        private Set<String> subjects = Set.of();
        private String contains;
        private String note;
        private long cooldownMs;
        private int maxFires;

        public Builder kind(Signal.Kind kind) {
            this.kind = kind;
            return this;
        }

        /** Any subject (default). */
        public Builder any() {
            this.subjects = Set.of();
            return this;
        }

        /** Only these registry IDs (case-insensitive). */
        public Builder subjects(String... ids) {
            this.subjects = List.of(ids).stream()
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            return this;
        }

        /** CHAT only: fire when the message contains this keyword. */
        public Builder contains(String keyword) {
            this.contains = keyword.toLowerCase(Locale.ROOT);
            this.subjects = Set.of();
            return this;
        }

        /** Context appended to the LLM prompt when this watch fires. */
        public Builder note(String note) {
            this.note = note;
            return this;
        }

        public Builder cooldownMs(long ms) {
            this.cooldownMs = ms;
            return this;
        }

        public Builder maxFires(int n) {
            this.maxFires = n;
            return this;
        }

        public Watch build() {
            if (kind == null) {
                throw new IllegalStateException("Watch needs a kind");
            }
            return new Watch(this);
        }
    }
}
