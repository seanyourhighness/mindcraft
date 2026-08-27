package net.clankerjockey.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.clankerjockey.core.agent.Signal;
import net.clankerjockey.core.agent.Watch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

class SignalBridgeTest {

    @Test
    void creeperMobSignalBecomesHostileEvent() {
        SignalBridge bridge = new SignalBridge(SignalBridge.defaultWatches());
        Optional<SemanticEvent> e = bridge.assess(Signal.mob("minecraft:creeper"));
        assertTrue(e.isPresent(), "watched mob must become an event");
        assertEquals(EventPriority.P2, e.get().priority(), "hostile mob near player is P2");
        assertTrue(e.get().description().contains("creeper"));
        assertTrue(e.get().type().equals("HOSTILE_SPAWNED"));
    }

    @Test
    void uninterestingSignalsAreIgnored() {
        SignalBridge bridge = new SignalBridge(SignalBridge.defaultWatches());
        assertTrue(bridge.assess(Signal.mob("minecraft:cow")).isEmpty(),
                "an unwatched mob must not reach the agent");
        assertTrue(bridge.assess(Signal.blockBreak("minecraft:grass_block")).isEmpty(),
                "ordinary mining must not reach the agent");
    }

    @Test
    void curatedDiamondBreakFiresThenCooldownSuppressesRepeat() {
        SignalBridge bridge = new SignalBridge(SignalBridge.defaultWatches());
        Optional<SemanticEvent> first = bridge.assess(Signal.blockBreak("minecraft:diamond_ore"));
        assertTrue(first.isPresent());
        assertEquals(EventPriority.P5, first.get().priority());
        assertTrue(first.get().description().contains("diamond ore"));

        assertTrue(bridge.assess(Signal.blockBreak("minecraft:diamond_ore")).isEmpty(),
                "the same curated signal on cooldown must be suppressed");
    }

    @Test
    void chatKeywordWatchBecomesPlayerEvent() {
        SignalBridge bridge = new SignalBridge(List.of(
                Watch.builder().kind(Signal.Kind.CHAT).contains("help").build()));
        Optional<SemanticEvent> e = bridge.assess(Signal.chat("can you help me"));
        assertTrue(e.isPresent());
        assertEquals(EventPriority.P1, e.get().priority(), "player chat is a direct request");
        assertTrue(e.get().description().contains("help me"));
    }

    @Test
    void biomeAndItemUseSignalsFire() {
        SignalBridge bridge = new SignalBridge(SignalBridge.defaultWatches());
        Optional<SemanticEvent> biome = bridge.assess(Signal.biome("minecraft:crimson_forest"));
        assertTrue(biome.isPresent());
        assertTrue(biome.get().description().contains("crimson forest"));

        Optional<SemanticEvent> item = bridge.assess(Signal.itemUse("minecraft:ender_pearl"));
        assertTrue(item.isPresent());
        assertTrue(item.get().description().contains("ender pearl"));
    }
}
