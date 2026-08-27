package net.mindcraft.core.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeTest {

    /** Deterministic reasoner: returns whatever the test sets. */
    private static final class FakeReasoner implements AgentRuntime.Reasoner {
        String reply = "On my way!";
        int calls;

        @Override
        public String reason(String prompt) {
            calls++;
            return reply;
        }
    }

    @Test
    void watchMatchesKindAndSubject() {
        Watch anyMob = Watch.builder().kind(Signal.Kind.MOB).any().build();
        Watch onlyCreeper = Watch.builder().kind(Signal.Kind.MOB).subjects("minecraft:creeper").build();
        Watch biome = Watch.builder().kind(Signal.Kind.BIOME).subjects("minecraft:crimson_forest").build();

        assertTrue(anyMob.matches(Signal.mob("minecraft:zombie")));
        assertTrue(onlyCreeper.matches(Signal.mob("minecraft:CREEPER"))); // case-insensitive
        assertFalse(onlyCreeper.matches(Signal.mob("minecraft:zombie")));
        assertFalse(onlyCreeper.matches(Signal.biome("minecraft:creeper"))); // kind differs
        assertTrue(biome.matches(Signal.biome("minecraft:crimson_forest")));
        assertFalse(biome.matches(Signal.mob("minecraft:crimson_forest")));
    }

    @Test
    void chatContainsKeyword() {
        Watch help = Watch.builder().kind(Signal.Kind.CHAT).contains("help").build();
        assertTrue(help.matches(Signal.chat("HELP me find diamonds")));
        assertTrue(help.matches(Signal.chat("anyone want to help?")));
        assertFalse(help.matches(Signal.chat("hello there")));
    }

    @Test
    void toolCallRoutesToToolSink() {
        FakeReasoner llm = new FakeReasoner();
        llm.reply = "Sure! {\"tool\":\"give_item\",\"args\":{\"item\":\"minecraft:diamond\",\"count\":3}}";
        AtomicReference<String> tool = new AtomicReference<>();
        AtomicReference<Map<String, Object>> args = new AtomicReference<>();
        AtomicInteger speech = new AtomicInteger();

        AgentRuntime agent = new AgentRuntime(llm)
                .watch(Watch.builder().kind(Signal.Kind.MOB).subjects("minecraft:enderman").build())
                .toolSink((name, a) -> {
                    tool.set(name);
                    args.set(a);
                })
                .speechSink(line -> speech.incrementAndGet());

        Watch fired = agent.observe(Signal.mob("minecraft:enderman"));
        assertNotNull(fired);
        assertEquals("give_item", tool.get());
        assertEquals("minecraft:diamond", args.get().get("item"));
        assertEquals(3L, args.get().get("count"));
        assertEquals(0, speech.get());
        assertEquals(1, llm.calls);
    }

    @Test
    void plainTextRoutesToSpeechSink() {
        FakeReasoner llm = new FakeReasoner(); // default reply "On my way!"
        AtomicInteger speech = new AtomicInteger();
        AtomicReference<String> line = new AtomicReference<>();

        AgentRuntime agent = new AgentRuntime(llm)
                .watch(Watch.builder().kind(Signal.Kind.BIOME).subjects("minecraft:snowy_tundra").build())
                .speechSink(l -> {
                    line.set(l);
                    speech.incrementAndGet();
                });

        agent.observe(Signal.biome("minecraft:snowy_tundra"));
        assertEquals(1, speech.get());
        assertEquals("On my way!", line.get());
        assertEquals(1, llm.calls);
    }

    @Test
    void cooldownSuppressesRapidRefires() {
        FakeReasoner llm = new FakeReasoner();
        AgentRuntime agent = new AgentRuntime(llm)
                .watch(Watch.builder().kind(Signal.Kind.BIOME)
                        .subjects("minecraft:crimson_forest")
                        .cooldownMs(60_000)
                        .build());

        Watch first = agent.observe(Signal.biome("minecraft:crimson_forest"));
        Watch second = agent.observe(Signal.biome("minecraft:crimson_forest"));
        assertNotNull(first);
        assertNull(second); // within cooldown
        assertEquals(1, llm.calls); // LLM pinged once, not twice
    }

    @Test
    void maxFiresStopsAfterN() {
        FakeReasoner llm = new FakeReasoner();
        AgentRuntime agent = new AgentRuntime(llm)
                .watch(Watch.builder().kind(Signal.Kind.MOB)
                        .subjects("minecraft:witherskeleton")
                        .maxFires(2)
                        .build());

        assertNotNull(agent.observe(Signal.mob("minecraft:witherskeleton")));
        assertNotNull(agent.observe(Signal.mob("minecraft:witherskeleton")));
        assertNull(agent.observe(Signal.mob("minecraft:witherskeleton")));
        assertEquals(2, llm.calls);
    }

    @Test
    void noMatchMeansNoLlmCall() {
        FakeReasoner llm = new FakeReasoner();
        AgentRuntime agent = new AgentRuntime(llm)
                .watch(Watch.builder().kind(Signal.Kind.MOB).subjects("minecraft:creeper").build());

        assertNull(agent.observe(Signal.mob("minecraft:zombie")));
        assertEquals(0, llm.calls);
    }

    @Test
    void extractJsonFindsBalancedBlock() {
        assertEquals(
                "{\"tool\":\"stop\"}",
                AgentRuntime.extractJson("Okay! {\"tool\":\"stop\"} bye"));
        assertEquals(
                "{\"a\":{\"b\":1}}",
                AgentRuntime.extractJson("prefix {\"a\":{\"b\":1}} suffix"));
        assertNull(AgentRuntime.extractJson("no json here"));
        // string containing braces must not confuse the scanner
        assertEquals(
                "{\"tool\":\"say\",\"args\":{\"text\":\"{hi}\"}}",
                AgentRuntime.extractJson("{\"tool\":\"say\",\"args\":{\"text\":\"{hi}\"}}"));
    }
}
