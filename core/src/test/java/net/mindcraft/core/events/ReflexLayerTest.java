package net.mindcraft.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.mindcraft.core.agent.TestWorld;
import net.mindcraft.core.world.EntityInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

class ReflexLayerTest {

    @Test
    void calmWorldTriggersNoReflex() {
        TestWorld world = new TestWorld();
        Optional<ReflexLayer.ReflexResult> r = new ReflexLayer().tick(world);
        assertTrue(r.isEmpty());
        assertEquals(0, world.moveAwayCalls);
    }

    @Test
    void imminentHostileTriggersEvade() {
        TestWorld world = new TestWorld();
        world.entities = List.of(new EntityInfo("creeper", 2.5, 3, 64, 1, true, "behind-right"));
        Optional<ReflexLayer.ReflexResult> r = new ReflexLayer().tick(world);

        assertTrue(r.isPresent());
        assertEquals("evaded", r.get().action());
        assertTrue(r.get().reason().contains("creeper"));
        assertEquals(1, world.moveAwayCalls);
        assertTrue(r.get().toEvent().renderLine().startsWith("[P0] REFLEX"));
    }

    @Test
    void distantHostileDoesNotTriggerEvade() {
        TestWorld world = new TestWorld();
        world.entities = List.of(new EntityInfo("zombie", 7.0, 8, 64, 1, true, "front"));
        assertTrue(new ReflexLayer().tick(world).isEmpty());
        assertEquals(0, world.moveAwayCalls);
    }

    @Test
    void friendlyCowDoesNotTriggerEvade() {
        TestWorld world = new TestWorld();
        world.entities = List.of(new EntityInfo("cow", 2.0, 2, 64, 1, false, "front"));
        assertTrue(new ReflexLayer().tick(world).isEmpty());
    }

    @Test
    void criticallyLowHealthTriggersRetreat() {
        TestWorld world = new TestWorld();
        world.state = new net.mindcraft.core.world.SelfState(1, 64, 2, "overworld", "plains",
                "day", "clear", 3.0, 20, "survival", null);
        Optional<ReflexLayer.ReflexResult> r = new ReflexLayer().tick(world);
        assertTrue(r.isPresent());
        assertTrue(r.get().reason().contains("health"));
        assertEquals(1, world.moveAwayCalls);
    }
}
