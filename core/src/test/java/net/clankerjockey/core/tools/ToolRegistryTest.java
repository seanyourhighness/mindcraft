package net.clankerjockey.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.clankerjockey.core.agent.AgentContext;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    @Test
    void registerLookupAndNames() {
        ToolRegistry reg = new ToolRegistry();
        Tool tool = new TestTool("hello", "Says hello.");
        reg.register(tool);

        assertEquals(1, reg.size());
        assertSameInstance(tool, reg.get("hello"));
        assertTrue(reg.contains("hello"));
        assertEquals("hello", reg.names().get(0));
        assertEquals("hello", reg.definitions().get(0).name());
    }

    @Test
    void duplicateRegistrationRejected() {
        ToolRegistry reg = new ToolRegistry();
        reg.register(new TestTool("same", "one"));
        assertThrows(IllegalArgumentException.class,
                () -> reg.register(new TestTool("same", "two")));
    }

    @Test
    void missingToolReturnsNull() {
        ToolRegistry reg = new ToolRegistry();
        assertNull(reg.get("nope"));
        assertTrue(!reg.contains("nope"));
    }

    private static void assertSameInstance(Tool expected, Tool actual) {
        assertNotNull(actual);
        assertEquals(expected.definition().name(), actual.definition().name());
    }

    /** Minimal tool for registry tests. */
    static final class TestTool implements Tool {
        private final ToolDefinition def;

        TestTool(String name, String description) {
            this.def = ToolDefinition.query(name, description);
        }

        @Override
        public ToolDefinition definition() {
            return def;
        }

        @Override
        public ToolResult execute(ToolCall call, AgentContext context) {
            return ToolResult.success(def.name(), "ok", java.util.Map.of());
        }
    }
}
