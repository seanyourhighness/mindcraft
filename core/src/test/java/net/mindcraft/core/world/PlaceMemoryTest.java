package net.mindcraft.core.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class PlaceMemoryTest {

    @TempDir
    Path tmp;

    @Test
    void rememberRecallAndPersist() throws Exception {
        Path file = tmp.resolve("places.json");
        PlaceMemory mem = new PlaceMemory(file);
        mem.remember("Home", 10.5, 64, -20);
        mem.remember("mine", -100, 12, 300);

        assertTrue(mem.recall("home").isPresent(), "recall must be case-insensitive");
        assertEquals(10.5, mem.recall("HOME").get().x(), 0.001);
        assertEquals(2, mem.all().size());
        assertTrue(mem.names().contains("Home"));

        // Reload from disk and verify persistence.
        PlaceMemory reloaded = new PlaceMemory(file);
        assertEquals(2, reloaded.all().size());
        assertTrue(reloaded.recall("mine").isPresent());
        assertEquals(-100, reloaded.recall("mine").get().x(), 0.001);
    }

    @Test
    void forgetRemovesAndPersists() throws Exception {
        PlaceMemory mem = new PlaceMemory(tmp.resolve("places.json"));
        mem.remember("home", 1, 2, 3);
        assertTrue(mem.forget("HOME"));
        assertFalse(mem.recall("home").isPresent());
        assertFalse(mem.forget("home"), "second forget must report nothing removed");
        assertEquals(0, new PlaceMemory(tmp.resolve("places.json")).all().size());
    }
}
