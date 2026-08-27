package net.clankerjockey.core.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Map;

class AgentTaskTest {

    @Test
    void lifecycleRunsThroughTerminalStates() {
        AgentTask task = new AgentTask("task-1", "Collect 32 iron ore");
        assertEquals(TaskStatus.PENDING, task.status());

        task.start();
        assertEquals(TaskStatus.RUNNING, task.status());
        assertFalse(task.isTerminal());

        task.update(TaskStatus.SUCCEEDED, "Collected 32 iron ore.", Map.of("collected", 32));
        assertTrue(task.isTerminal());
        assertEquals(TaskStatus.SUCCEEDED, task.status());
        assertEquals("Collected 32 iron ore.", task.message());
        assertEquals(32, task.data().get("collected"));
        assertTrue(task.render().contains("\"status\":\"succeeded\""));
    }

    @Test
    void terminalStateCannotChange() {
        AgentTask task = new AgentTask("task-1", "dig");
        task.start();
        task.update(TaskStatus.BLOCKED, "No path.", Map.of());
        assertThrows(IllegalStateException.class,
                () -> task.update(TaskStatus.RUNNING, "back to work", Map.of()));
        assertEquals(TaskStatus.BLOCKED, task.status());
    }

    @Test
    void cancelMarksCancelledOnce() {
        AgentTask task = new AgentTask("task-1", "build");
        task.start();
        assertTrue(task.requestCancel());
        assertEquals(TaskStatus.CANCELLED, task.status());
        assertFalse(task.requestCancel(), "cancelling a terminal task must fail");
        assertTrue(task.isTerminal());
    }
}
