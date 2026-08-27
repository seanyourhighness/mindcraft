package net.mindcraft.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.mindcraft.core.world.SelfState;
import org.junit.jupiter.api.Test;

import java.util.Optional;

class WorldAwarenessTest {

    private static SelfState state(String time, String weather, String dim) {
        return new SelfState(1, 64, 2, dim, "plains", time, weather, 20, 20, "survival", null);
    }

    @Test
    void sunsetAndNightFireOncePerTransition() {
        WorldAwareness aw = new WorldAwareness();
        assertTrue(aw.tick(state("morning", "clear", "overworld")).isEmpty());
        assertTrue(aw.tick(state("morning", "clear", "overworld")).isEmpty(),
                "unchanged state must not re-fire");

        Optional<SemanticEvent> sunset = aw.tick(state("evening", "clear", "overworld"));
        assertTrue(sunset.isPresent());
        assertEquals("SUNSET", sunset.get().type());

        Optional<SemanticEvent> night = aw.tick(state("night", "clear", "overworld"));
        assertTrue(night.isPresent());
        assertEquals("NIGHT", night.get().type());

        assertTrue(aw.tick(state("night", "clear", "overworld")).isEmpty());
    }

    @Test
    void weatherAndDimensionChangesFire() {
        WorldAwareness aw = new WorldAwareness();
        aw.tick(state("afternoon", "clear", "overworld"));

        Optional<SemanticEvent> rain = aw.tick(state("afternoon", "rain", "overworld"));
        assertTrue(rain.isPresent());
        assertEquals("WEATHER_CHANGE", rain.get().type());
        assertTrue(rain.get().description().contains("rain"));

        Optional<SemanticEvent> dim = aw.tick(state("afternoon", "rain", "the_nether"));
        assertTrue(dim.isPresent());
        assertEquals("DIMENSION_CHANGE", dim.get().type());
        assertTrue(dim.get().description().contains("the_nether"));
    }

    @Test
    void unknownWorldStatesAreIgnored() {
        WorldAwareness aw = new WorldAwareness();
        assertTrue(aw.tick(state("unknown", "clear", "unavailable")).isEmpty());
        assertTrue(aw.tick(state("morning", "clear", "overworld")).isEmpty());
    }
}
