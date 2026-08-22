package net.mindcraft.core.memory;

import net.mindcraft.core.engine.MiniJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent per-player memory ledger: durable facts, a rolling summary and a
 * simple relationship score, stored as JSON at {@code <dir>/<world>/<player>.json}.
 *
 * <p>Design: the LLM never sees the full history — the caller injects the
 * ledger (facts + summary) between the system prompt and the last K raw turns.
 * Old exchanges are distilled via a summarizer callback into facts so memory
 * persists across sessions without needing long context.</p>
 *
 * <p>Thread safety: synchronized; all mutation goes through
 * {@link #recordTurn} / {@link #maybeSummarize}.</p>
 */
public final class MemoryLedger {

    /** One user/assistant exchange, pre-summarization. */
    public record Turn(String user, String assistant) {}

    /** Callback that compresses a batch of turns into durable facts + updated summary. */
    public interface Summarizer {
        /**
         * @param existingSummary current summary text (may be empty)
         * @param turns           batch of raw exchanges to distill
         * @return new facts (each a short self-contained sentence) and the
         *         updated summary; both lists may be empty but must not be null
         */
        Result summarize(String existingSummary, List<Turn> turns);
    }

    /** Output of a {@link Summarizer} call. */
    public record Result(List<String> newFacts, String summary) {
        public Result {
            if (newFacts == null) newFacts = List.of();
            if (summary == null) summary = "";
        }
    }

    /** Summarizer that may throw; lets callers use engine calls inline. */
    @FunctionalInterface
    public interface ThrowingSummarizer extends Summarizer {
        @Override
        default Result summarize(String existingSummary, List<Turn> turns) {
            try {
                return summarizeChecked(existingSummary, turns);
            } catch (Exception e) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
        }

        Result summarizeChecked(String existingSummary, List<Turn> turns) throws Exception;
    }

    private static final int MAX_FACTS = 40;
    private static final int MAX_SUMMARY_CHARS = 600;

    private final Path file;
    private final List<String> facts = new ArrayList<>();
    private final List<String> insideJokes = new ArrayList<>();
    private final List<Turn> pending = new ArrayList<>();
    private String summary = "";
    private int trust = 5;

    public MemoryLedger(Path dir, String worldId, String playerId) throws IOException {
        this.file = dir.resolve(sanitize(worldId)).resolve(sanitize(playerId) + ".json");
        Files.createDirectories(file.getParent());
        load();
    }

    /** The ledger file backing this instance. */
    public Path file() {
        return file;
    }

    public List<String> facts() {
        synchronized (this) {
            return List.copyOf(facts);
        }
    }

    public String summary() {
        synchronized (this) {
            return summary;
        }
    }

    public int trust() {
        synchronized (this) {
            return trust;
        }
    }

    /** Manually record a durable fact (e.g. parsed from an explicit "remember ..."). */
    public void addFact(String fact) throws IOException {
        synchronized (this) {
            if (fact != null && !fact.isBlank() && !facts.contains(fact)) {
                facts.add(fact.trim());
                trimFacts();
                save();
            }
        }
    }

    public void addInsideJoke(String joke) throws IOException {
        synchronized (this) {
            if (joke != null && !joke.isBlank() && !insideJokes.contains(joke)) {
                insideJokes.add(joke.trim());
                save();
            }
        }
    }

    public void adjustTrust(int delta) throws IOException {
        synchronized (this) {
            trust = Math.max(0, Math.min(10, trust + delta));
            save();
        }
    }

    /** Queue a completed exchange; returns number of pending (unsummarized) turns. */
    public int recordTurn(String user, String assistant) {
        synchronized (this) {
            pending.add(new Turn(user, assistant));
            return pending.size();
        }
    }

    /** Pending (not yet summarized) turn count. */
    public int pendingCount() {
        synchronized (this) {
            return pending.size();
        }
    }

    /**
     * If {@code threshold} pending turns have accumulated, run the summarizer
     * and persist. Returns true when summarization happened.
     */
    public boolean maybeSummarize(int threshold, Summarizer summarizer) throws IOException {
        if (summarizer == null) {
            return false;
        }
        final List<Turn> batch;
        synchronized (this) {
            if (pending.size() < threshold) {
                return false;
            }
            batch = new ArrayList<>(pending);
            pending.clear();
        }
        Result r = summarizer.summarize(summary, batch);
        synchronized (this) {
            for (String f : r.newFacts()) {
                if (!f.isBlank() && !facts.contains(f.trim())) {
                    facts.add(f.trim());
                }
            }
            trimFacts();
            if (!r.summary().isBlank()) {
                summary = r.summary().length() > MAX_SUMMARY_CHARS
                        ? r.summary().substring(0, MAX_SUMMARY_CHARS)
                        : r.summary();
            }
            save();
        }
        return true;
    }

    /**
     * Render the memory block injected between the system prompt and recent
     * history. Empty when the ledger has nothing to say yet.
     */
    public String render() {
        synchronized (this) {
            StringBuilder sb = new StringBuilder();
            if (!summary.isBlank()) {
                sb.append("Backstory: ").append(summary).append('\n');
            }
            if (!facts.isEmpty()) {
                sb.append("Things you remember:\n");
                for (String f : facts) {
                    sb.append("- ").append(f).append('\n');
                }
            }
            if (!insideJokes.isEmpty()) {
                sb.append("Inside jokes: ").append(String.join("; ", insideJokes)).append('\n');
            }
            return sb.toString().stripTrailing();
        }
    }

    // --- persistence -------------------------------------------------------

    private void trimFacts() {
        while (facts.size() > MAX_FACTS) {
            facts.remove(0); // oldest facts fall off; the summary carries their gist
        }
    }

    private void load() throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Object tree = MiniJson.parse(Files.readString(file, StandardCharsets.UTF_8));
        summary = orEmpty(MiniJson.stringAt(tree, "summary"));
        Object f = MiniJson.at(tree, "facts");
        if (f instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) {
                    facts.add(s);
                }
            }
        }
        Object ij = MiniJson.at(tree, "relationship.inside_jokes");
        if (ij instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) {
                    insideJokes.add(s);
                }
            }
        }
        Object t = MiniJson.at(tree, "relationship.trust");
        if (t instanceof Number n) {
            trust = Math.max(0, Math.min(10, n.intValue()));
        }
    }

    private void save() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("summary", summary);
        root.put("facts", new ArrayList<>(facts));
        Map<String, Object> rel = new LinkedHashMap<>();
        rel.put("trust", trust);
        rel.put("inside_jokes", new ArrayList<>(insideJokes));
        root.put("relationship", rel);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, MiniJson.stringify(root), StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static String sanitize(String s) {
        String cleaned = s.replaceAll("[^a-zA-Z0-9_-]", "_");
        return cleaned.isBlank() ? "_" : cleaned;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
