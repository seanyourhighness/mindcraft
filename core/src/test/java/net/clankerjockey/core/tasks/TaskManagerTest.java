package net.clankerjockey.core.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Map;

class TaskManagerTest {

    @Test
    void startCreatesUniqueRunningTasks() {
        TaskManager mgr = new TaskManager();
        AgentTask a = mgr.start("collect wood");
        AgentTask b = mgr.start("build house");

        assertNotEquals(a.id(), b.id(), "task ids must be unique");
        assertTrue(a.id().startsWith("task-"));
        assertEquals(TaskStatus.RUNNING, a.status());
        assertEquals(2, mgr.all().size());
        assertEquals(2, mgr.active().size());
        assertTrue(mgr.get(a.id()).isPresent());
    }

    @Test
    void cancelAndUpdateReflectInManager() {
        TaskManager mgr = new TaskManager();
        AgentTask task = mgr.start("mine");

        assertTrue(mgr.cancel(task.id()));
        assertEquals(TaskStatus.CANCELLED, mgr.get(task.id()).get().status());
        assertTrue(mgr.active().isEmpty(), "cancelled tasks are no longer active");

        AgentTask other = mgr.start("fish");
        mgr.update(other.id(), TaskStatus.SUCCEEDED, "Caught fish.", Map.of("count", 3));
        assertEquals("Caught fish.", mgr.get(other.id()).get().message());
    }

    @Test
    void unknownIdBehaviors() {
        TaskManager mgr = new TaskManager();
        assertFalse(mgr.cancel("task-999"));
        assertTrue(mgr.get("task-999").isEmpty());
        assertFalse(mgr.get(null).isPresent());
    }
}
