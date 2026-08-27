package net.clankerjockey.core.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryLedgerTest {

    @TempDir
    Path tmp;

    @Test
    void persistsAcrossInstances() throws IOException {
        MemoryLedger a = new MemoryLedger(tmp, "world1", "Steve");
        a.addFact("Steve built a base at spawn");
        a.addInsideJoke("the creeper incident");
        a.adjustTrust(2);

        MemoryLedger b = new MemoryLedger(tmp, "world1", "Steve");
        assertEquals(List.of("Steve built a base at spawn"), b.facts());
        assertEquals(7, b.trust());
        assertTrue(b.render().contains("creeper incident"));
    }

    @Test
    void sanitizesUnsafeIds() throws IOException {
        new MemoryLedger(tmp, "../../evil", "pl/ay er").addFact("x");
        // must stay inside tmp, never traverse out
        Path ledgerFile = tmp.resolve("__").resolve("__").resolve("_evil");
        assertTrue(Files.exists(ledgerFile.resolve("pl_ay_er.json"))
                || Files.walk(tmp).filter(p -> p.getFileName().toString().equals("pl_ay_er.json")).findFirst().isPresent());
        assertTrue(ledgerFile.normalize().startsWith(tmp));
    }

    @Test
    void summarizerRunsAtThresholdAndPersists() throws IOException {
        MemoryLedger l = new MemoryLedger(tmp, "w", "p");
        MemoryLedger.Summarizer s = (summary, turns) -> new MemoryLedger.Result(
                List.of("Player tamed a wolf"), "Summary: " + summary + "tamed wolf.");

        l.recordTurn("tamed a wolf", "nice!");
        assertFalse(l.maybeSummarize(2, s)); // below threshold, no call
        l.recordTurn("then a cat", "cute");
        assertTrue(l.maybeSummarize(2, s));  // threshold reached
        assertEquals(0, l.pendingCount());

        MemoryLedger reloaded = new MemoryLedger(tmp, "w", "p");
        assertTrue(reloaded.facts().contains("Player tamed a wolf"));
        assertEquals("Summary: tamed wolf.", reloaded.summary());
        assertTrue(reloaded.render().contains("tamed wolf"));
    }

    @Test
    void factCapDropsOldest() throws IOException {
        MemoryLedger l = new MemoryLedger(tmp, "w", "p");
        for (int i = 0; i < 45; i++) {
            l.addFact("fact" + i);
        }
        assertEquals(40, l.facts().size());
        assertFalse(l.facts().contains("fact0"));
        assertTrue(l.facts().contains("fact44"));
    }

    @Test
    void noDuplicateFacts() throws IOException {
        MemoryLedger l = new MemoryLedger(tmp, "w", "p");
        l.addFact("same");
        l.addFact("same");
        assertEquals(1, l.facts().size());
    }

    @Test
    void trustClampedToRange() throws IOException {
        MemoryLedger l = new MemoryLedger(tmp, "w", "p");
        l.adjustTrust(-99);
        assertEquals(0, l.trust());
        l.adjustTrust(+99);
        assertEquals(10, l.trust());
    }

    @Test
    void renderEmptyWhenNew() throws IOException {
        MemoryLedger l = new MemoryLedger(tmp, "w", "p");
        assertEquals("", l.render());
    }
}
