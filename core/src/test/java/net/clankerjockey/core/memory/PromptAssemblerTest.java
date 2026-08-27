package net.clankerjockey.core.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptAssemblerTest {

    private static final String SYS = "You are Vera, a friendly mining companion.";

    @Test
    void basicLayout() {
        String out = PromptAssembler.build(SYS, null, List.of(), "hello");
        assertTrue(out.startsWith(SYS));
        assertTrue(out.endsWith("Player: hello\nYou:"));
        assertFalse(out.contains("Recent conversation"));
    }

    @Test
    void ledgerInjectedBetweenSystemAndHistory() {
        List<PromptAssembler.Turn> hist =
                List.of(new PromptAssembler.Turn("hi", "hello!"));
        String out = PromptAssembler.build(SYS, "Backstory: old friends\nThings you remember:\n- dog named Biscuit",
                hist, 8, "again");
        int sys = out.indexOf(SYS);
        int ledger = out.indexOf("Backstory: old friends");
        int biscuit = out.indexOf("Biscuit");
        int recent = out.indexOf("Recent conversation:");
        int msg = out.lastIndexOf("Player: again");
        assertTrue(sys < ledger && ledger < biscuit && biscuit < recent && recent < msg);
    }

    @Test
    void windowKeepsOnlyTail() {
        List<PromptAssembler.Turn> hist = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            hist.add(new PromptAssembler.Turn("u" + i, "a" + i));
        }
        String out = PromptAssembler.build(SYS, null, hist, 8, "now");
        assertTrue(out.contains("u19"));
        assertFalse(out.contains("u11"));
        assertFalse(out.contains("u0\n"));
    }

    @Test
    void blankSystemRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PromptAssembler.build("  ", null, List.of(), "x"));
    }

    @Test
    void emptyHistoryOmitsRecentBlock() {
        String out = PromptAssembler.build(SYS, "", List.of(), "q");
        assertFalse(out.contains("Recent"));
        // no double blank lines where ledger/history would go
        assertFalse(out.contains("\n\n\n"));
    }
}
