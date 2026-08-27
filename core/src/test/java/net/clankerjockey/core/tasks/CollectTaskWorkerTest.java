package net.clankerjockey.core.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.clankerjockey.core.agent.TestWorld;
import net.clankerjockey.core.world.BlockInfo;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

class CollectTaskWorkerTest {

    private static AgentTask collectTask(TaskManager mgr, String block, int count) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("type", CollectTaskWorker.TYPE);
        meta.put("block", block);
        meta.put("count", count);
        return mgr.start("collect", meta);
    }

    @Test
    void searchesWalksBreaksAndSucceeds() {
        TestWorld world = new TestWorld();
        world.nearestBlock = Optional.of(new BlockInfo("iron_ore", 5, 63, 8, 6.4));
        TaskManager mgr = new TaskManager();
        AgentTask task = collectTask(mgr, "iron_ore", 1);
        CollectTaskWorker worker = new CollectTaskWorker(world);

        assertFalse(worker.tick(task, "iron_ore", 1), "search phase navigates, does not finish");
        assertEquals(TaskStatus.RUNNING, task.status());
        assertTrue(task.data().get("phase").toString().equals("walk"));
        assertEquals(1, world.goToCalls);

        assertFalse(worker.tick(task, "iron_ore", 1), "arrival phase switches to breaking");
        assertTrue(worker.tick(task, "iron_ore", 1), "breaking completes the task");

        assertEquals(TaskStatus.SUCCEEDED, task.status());
        assertEquals(1, world.items.get("iron_ore"));
        assertEquals(1, task.data().get("collected"));
        assertTrue(task.render().contains("\"status\":\"succeeded\""));
    }

    @Test
    void collectsUntilTargetCount() {
        TestWorld world = new TestWorld();
        world.nearestBlock = Optional.of(new BlockInfo("oak_log", 3, 64, 3, 4.2));
        TaskManager mgr = new TaskManager();
        AgentTask task = collectTask(mgr, "oak_log", 2);
        CollectTaskWorker worker = new CollectTaskWorker(world);

        for (int i = 0; i < 20 && !task.isTerminal(); i++) {
            worker.tick(task, "oak_log", 2);
        }

        assertEquals(TaskStatus.SUCCEEDED, task.status());
        assertEquals(2, world.items.get("oak_log"));
        assertEquals(2, task.data().get("collected"));
    }

    @Test
    void missingBlockBlocksTask() {
        TestWorld world = new TestWorld();
        TaskManager mgr = new TaskManager();
        AgentTask task = collectTask(mgr, "diamond_ore", 1);
        CollectTaskWorker worker = new CollectTaskWorker(world);

        assertTrue(worker.tick(task, "diamond_ore", 1));
        assertEquals(TaskStatus.BLOCKED, task.status());
        assertTrue(task.message().contains("No diamond_ore"));
    }

    @Test
    void cancelledTaskStopsImmediately() {
        TestWorld world = new TestWorld();
        world.nearestBlock = Optional.of(new BlockInfo("stone", 2, 64, 2, 2.8));
        TaskManager mgr = new TaskManager();
        AgentTask task = collectTask(mgr, "stone", 5);
        task.requestCancel();
        CollectTaskWorker worker = new CollectTaskWorker(world);

        assertTrue(worker.tick(task, "stone", 5), "cancelled tasks must be terminal immediately");
        assertEquals(TaskStatus.CANCELLED, task.status());
        assertEquals(0, world.goToCalls);
    }
}
