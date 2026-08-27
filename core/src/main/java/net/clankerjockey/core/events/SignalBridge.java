package net.clankerjockey.core.events;

import net.clankerjockey.core.agent.Signal;
import net.clankerjockey.core.agent.Watch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Bridges the sensor layer ({@link Signal}) to the semantic event pipeline:
 * a watch set filters which signals matter, the {@link SalienceGate} applies
 * cooldown/repetition suppression (curated signals bypass the threshold but
 * never spam), and the resulting {@link SemanticEvent} feeds the agent's
 * proactive reaction path. Pure core, fully unit-testable.
 */
public final class SignalBridge {

    private final List<Watch> watches;
    private final SalienceGate gate;

    public SignalBridge(List<Watch> watches) {
        this(watches, new SalienceGate());
    }

    public SignalBridge(List<Watch> watches, SalienceGate gate) {
        this.watches = watches == null ? List.of() : List.copyOf(watches);
        this.gate = gate == null ? new SalienceGate() : gate;
    }

    /**
     * Convert a signal into a gated event. Returns empty when no watch
     * matches or the gate suppresses it (cooldown/repetition).
     */
    public Optional<SemanticEvent> assess(Signal signal) {
        if (signal == null) return Optional.empty();
        Watch watch = null;
        for (Watch w : watches) {
            if (w.matches(signal)) {
                watch = w;
                break;
            }
        }
        if (watch == null) return Optional.empty();
        SemanticEvent event = toEvent(signal, watch);
        return gate.assess(event, true).shouldNotify() ? Optional.of(event) : Optional.empty();
    }

    private static SemanticEvent toEvent(Signal s, Watch w) {
        String subject = pretty(s.subject);
        Map<String, Object> data = new LinkedHashMap<>();
        if (w.note != null && !w.note.isBlank()) {
            data.put("context", w.note);
        }
        return switch (s.kind) {
            case MOB -> SemanticEvent.of(EventPriority.P2, "HOSTILE_SPAWNED",
                    "a " + subject + " spawned near the player", data);
            case BIOME -> SemanticEvent.of(EventPriority.P5, "BIOME_CHANGE",
                    "you entered the " + subject + " biome", data);
            case STRUCTURE -> SemanticEvent.of(EventPriority.P5, "STRUCTURE_NEARBY",
                    "a " + subject + " structure is nearby", data);
            case ITEM_USE -> SemanticEvent.of(EventPriority.P5, "ITEM_USED",
                    "the player used a " + subject, data);
            case CHAT -> SemanticEvent.of(EventPriority.P1, "CHAT_KEYWORD",
                    "chat: \"" + s.subject + "\"", data);
            case BLOCK_BREAK -> SemanticEvent.of(EventPriority.P5, "BLOCK_MINED",
                    "the player mined " + subject, data);
        };
    }

    /**
     * The curated watch set (mob threats, biome changes, notable item use,
     * valuable ore mining). Chat is deliberately absent: player chat already
     * drives the primary agent turn.
     */
    public static List<Watch> defaultWatches() {
        List<Watch> out = new ArrayList<>();
        out.add(Watch.builder().kind(Signal.Kind.MOB)
                .subjects("minecraft:creeper", "minecraft:enderman",
                        "minecraft:skeleton", "minecraft:zombie_pigman")
                .note("A mob of interest spawned nearby. Warn the player or react in character.")
                .build());
        out.add(Watch.builder().kind(Signal.Kind.BIOME)
                .any()
                .note("The player entered a new biome. Comment on it or react.")
                .build());
        out.add(Watch.builder().kind(Signal.Kind.ITEM_USE)
                .subjects("minecraft:diamond_pickaxe", "minecraft:ender_pearl")
                .note("The player used a notable item.")
                .build());
        out.add(Watch.builder().kind(Signal.Kind.BLOCK_BREAK)
                .subjects("minecraft:diamond_ore", "minecraft:ancient_debris")
                .note("The player mined a valuable ore. React.")
                .build());
        return List.copyOf(out);
    }

    private static String pretty(String subject) {
        if (subject == null) return "something";
        String s = subject;
        if (s.startsWith("minecraft:")) {
            s = s.substring("minecraft:".length());
        }
        return s.replace('_', ' ').toLowerCase(Locale.ROOT);
    }
}
