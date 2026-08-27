package net.clankerjockey.core.events;

/** Outcome of salience gating for one event. */
public record SalienceDecision(
        SemanticEvent event,
        double score,
        boolean shouldNotify,
        String reason) {

    public static SalienceDecision pass(SemanticEvent e, double score) {
        return new SalienceDecision(e, score, true, "salient");
    }

    public static SalienceDecision suppress(SemanticEvent e, double score, String reason) {
        return new SalienceDecision(e, score, false, reason);
    }
}
