package net.clankerjockey.core.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class ContainerStoreTest {

    @TempDir
    Path tmp;

    @Test
    void openPutTakeViewAndPersist() throws Exception {
        Path file = tmp.resolve("containers.json");
        ContainerStore store = new ContainerStore(file);

        assertTrue(store.open("Chest"));
        assertFalse(store.open("chest"), "re-opening must report not-created");
        assertEquals(3, store.put("chest", "diamond", 3));
        assertEquals(5, store.put("chest", "diamond", 2));

        assertTrue(store.view("chest").isPresent());
        assertEquals(5, store.view("CHEST").get().get("diamond"));

        assertEquals(2, store.take("chest", "diamond", 2));
        assertEquals(3, store.take("chest", "diamond", 99), "cannot take more than held");
        assertEquals(0, store.take("chest", "diamond", 1));

        // Persistence across reload.
        store.put("chest", "iron_ingot", 7);
        ContainerStore reloaded = new ContainerStore(file);
        assertEquals(7, reloaded.view("chest").get().get("iron_ingot"));
    }

    @Test
    void unknownContainerBehaviors() throws Exception {
        ContainerStore store = new ContainerStore(tmp.resolve("containers.json"));
        assertTrue(store.view("missing").isEmpty());
        assertEquals(-1, store.take("missing", "diamond", 1));
        assertTrue(store.names().isEmpty());
    }
}
