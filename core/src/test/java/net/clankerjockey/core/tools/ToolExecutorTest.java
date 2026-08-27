package net.clankerjockey.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.clankerjockey.core.agent.AgentContext;
import net.clankerjockey.core.agent.TestWorld;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

class ToolExecutorTest {

    @Test
    void privilegedToolDeniedForNonOwner() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        reg.register(new PrivilegedTool());
        ToolExecutor executor = new ToolExecutor(reg);

        AgentContext ctx = AgentContext.builder("SomeoneElse", new TestWorld()).owner(false).build();
        ToolResult r = executor.execute(new ToolCall("give_all", Map.of()), ctx);

        assertEquals(ToolResult.Status.DENIED, r.status());
        executor.close();
    }

    @Test
    void unknownToolReturnsFailureWithAvailableNames() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        reg.register(new PrivilegedTool());
        ToolExecutor executor = new ToolExecutor(reg);

        ToolResult r = executor.execute(new ToolCall("missing", Map.of()),
                AgentContext.builder("Sean", new TestWorld()).owner(true).build());

        assertEquals(ToolResult.Status.FAILED, r.status());
        assertTrue(r.message().contains("missing"));
        assertTrue(r.message().contains("give_all"));
        executor.close();
    }

    /** Owner-only tool used to prove security enforcement below the model. */
    static final class PrivilegedTool implements Tool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "give_all", "Give everything away.", List.of(), false, false,
                Duration.ZERO, SecurityClass.PRIVILEGED);

        @Override
        public ToolDefinition definition() {
            return DEF;
        }

        @Override
        public ToolResult execute(ToolCall call, AgentContext context) {
            return ToolResult.success(DEF.name(), "done", Map.of());
        }
    }
}
