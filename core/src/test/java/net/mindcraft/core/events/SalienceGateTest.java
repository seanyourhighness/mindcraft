package net.mindcraft.core.events;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SalienceGateTest {

    private static SemanticEvent event(EventPriority p, String type, Double proximity) {
        return new SemanticEvent(p, type, "test", proximity, System.currentTimeMillis(), java.util.Map.of());
    }

    @Test
    void imminentCreeperIsAlwaysNotified() {
        SalienceGate gate = new SalienceGate();
        SalienceDecision d = gate.assess(event(EventPriority.P0, "HOSTILE_APPROACHING", 3.0));
        assertTrue(d.shouldNotify(), "a creeper 3m away must reach the LLM");
        assertTrue(d.score() >= 1.0);
    }

    @Test
    void grassNearbyIsIgnored() {
        SalienceGate gate = new SalienceGate();
        SalienceDecision d = gate.assess(event(EventPriority.P6, "BLOCK_SEEN", 2.0));
        assertFalse(d.shouldNotify(), "idle curiosity must be suppressed");
        assertTrue(d.reason().contains("threshold"));
    }

    @Test
    void repeatedSameTypeEventsAreSuppressedByCooldown() {
        SalienceGate gate = new SalienceGate();
        SalienceDecision first = gate.assess(event(EventPriority.P3, "ZOMBIE_NEARBY", 10.0));
        assertTrue(first.shouldNotify());
        SalienceDecision second = gate.assess(event(EventPriority.P3, "ZOMBIE_NEARBY", 10.0));
        assertFalse(second.shouldNotify(), "same zombie seconds later must be suppressed");
        assertTrue(second.reason().contains("cooldown"));
    }

    @Test
    void playerCommandAndCriticalHealthAlwaysNotify() {
        SalienceGate gate = new SalienceGate();
        assertTrue(gate.assess(event(EventPriority.P1, "PLAYER_CHAT", null)).shouldNotify());
        assertTrue(gate.assess(event(EventPriority.P0, "PLAYER_LOW_HEALTH", 2.0)).shouldNotify());
        // P1 ignores cooldown too
        assertTrue(gate.assess(event(EventPriority.P1, "PLAYER_CHAT", null)).shouldNotify());
    }

    @Test
    void diamondsDiscoveryIsInterestingEnough() {
        SalienceGate gate = new SalienceGate();
        SalienceDecision d = gate.assess(event(EventPriority.P5, "DISCOVERY", 6.0));
        assertTrue(d.shouldNotify(), "a discovery at 6m should pass the threshold");
    }
}
