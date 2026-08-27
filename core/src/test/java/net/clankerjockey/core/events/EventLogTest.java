package net.clankerjockey.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EventLogTest {

    @Test
    void boundedRenderAndClear() {
        EventLog log = new EventLog(3);
        log.add(SemanticEvent.of(EventPriority.P0, "REFLEX", "You evaded a creeper."));
        log.add(SemanticEvent.of(EventPriority.P5, "DISCOVERY", "Found diamonds."));
        log.add(SemanticEvent.of(EventPriority.P4, "TASK_PROGRESS", "Collected 2 of 32 iron ore."));
        log.add(SemanticEvent.of(EventPriority.P6, "SUNSET", "The sun is setting."));

        String rendered = log.render();
        assertTrue(rendered.startsWith("Recent events:"));
        assertTrue(rendered.contains("DISCOVERY"));
        assertTrue(rendered.contains("TASK_PROGRESS"));
        assertTrue(rendered.contains("SUNSET"));
        assertTrue(!rendered.contains("REFLEX"), "oldest event must be evicted");

        log.clear();
        assertTrue(log.isEmpty());
        assertEquals("", log.render());
    }
}
