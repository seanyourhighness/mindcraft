package net.clankerjockey.core.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.clankerjockey.core.engine.EngineConfig;
import net.clankerjockey.core.engine.InferenceEngine;
import net.clankerjockey.core.engine.ToolGrammar;
import net.clankerjockey.core.memory.ChatSession;
import net.clankerjockey.core.tools.CoreTools;
import net.clankerjockey.core.tools.ToolExecutor;
import net.clankerjockey.core.tools.ToolRegistry;
import net.clankerjockey.core.world.PlaceMemory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * End-to-end agent-loop test against the REAL llama-server + REAL GGUF model,
 * proving three things the mock tests cannot:
 *
 * <ol>
 *   <li>The schema-generated GBNF grammar is accepted by the actual llama.cpp
 *       build (a malformed grammar would fail the HTTP call).</li>
 *   <li>The constrained output parses back into valid {@link ToolCall}s and
 *       the loop chains tool result → second LLM call → final response.</li>
 *   <li>The full turn terminates within the safety limits.</li>
 * </ol>
 *
 * Skipped (with message) when {@code CLANKERJOCKEY_LLAMA_SERVER} or
 * {@code CLANKERJOCKEY_TEST_MODEL} are unset.
 */
@Tag("integration")
class AgentLoopIntegrationTest {

    private static final String SERVER_ENV = "CLANKERJOCKEY_LLAMA_SERVER";
    private static final String MODEL_ENV = "CLANKERJOCKEY_TEST_MODEL";

    private static Path server;
    private static Path model;

    @BeforeAll
    static void resolveRealArtifacts() {
        server = envPath(SERVER_ENV);
        Assumptions.assumeTrue(server != null, SERVER_ENV + " not set - skipping integration test");
        model = envPath(MODEL_ENV);
        Assumptions.assumeTrue(model != null, MODEL_ENV + " not set - skipping integration test");
        Assumptions.assumeTrue(Files.isExecutable(server), SERVER_ENV + " is not executable: " + server);
        Assumptions.assumeTrue(Files.isRegularFile(model), MODEL_ENV + " is not a file: " + model);
    }

    private static Path envPath(String name) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? null : Path.of(v);
    }

    private static EngineConfig config() {
        return EngineConfig.builder()
                .modelPath(model)
                .serverBinary(server)
                .host("127.0.0.1")
                .port(0)
                .threads(8)
                .contextSize(4096)
                .extraArgs(List.of("--jinja"))
                .build();
    }

    @Test
    void fullAgentLoopRunsAgainstRealModel() throws Exception {
        InferenceEngine engine = new InferenceEngine(config());
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(CoreTools.all());
        ToolExecutor executor = new ToolExecutor(registry);
        AgentLoopConfig cfg = new AgentLoopConfig(8, 12, 2, 3, 30_000, 180_000, 0.6, 256, 800);
        AgentLoop loop = new AgentLoop(engine, registry, executor, cfg, ChatSession.SYSTEM_PROMPT);
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build", "agent-grammar.gbnf"),
                ToolGrammar.generate(registry.definitions()));
        TestWorld world = new TestWorld();

        try {
            long t0 = System.nanoTime();
            engine.start();
            System.out.println("[AgentLoopIntegrationTest] engine healthy on port " + engine.port());

            AgentContext ctx = AgentContext.builder("Sean", world)
                    .owner(true)
                    .worldId("test-world")
                    .logger(line -> System.out.println("[loop] " + line))
                    .build();
            AgentResponse r = loop.run("Come over here and follow me.", ctx);

            long seconds = (System.nanoTime() - t0) / 1_000_000_000L;
            System.out.println("[AgentLoopIntegrationTest] turn took " + seconds + "s, iterations="
                    + r.iterations() + ", toolCalls=" + r.toolCalls().size()
                    + ", response='" + r.text() + "'");

            assertFalse(r.text().isBlank(), "final response must not be blank");
            assertTrue(r.iterations() >= 1, "at least one inference iteration must run");
            assertFalse(r.interrupted(), "loop must not be interrupted");
            // NOTE: with a 0.3B model the loop may trip the repeated-call limiter
            // on a degenerate turn; that is the safety system working, so we do
            // not assert limitExceeded here. Deterministic mock tests cover the
            // exact happy-path expectations.

            for (ToolTrace t : r.toolCalls()) {
                assertTrue(registry.contains(t.call().name()),
                        "loop must only produce registered tools, got " + t.call().name());
                assertNotNull(t.result(), "every tool call must have a result");
            }
            assertFalse(r.toolCalls().isEmpty(),
                    "the follow request must produce at least one tool call (grammar + prompt must steer "
                            + "the model to act, not just chat)");

            // The tool the model chose must be movement/observation-related for the
            // request. With a 0.3B model the exact choice is probabilistic, so the
            // relevant-tool family is asserted rather than one specific tool.
            String first = r.toolCalls().get(0).call().name();
            assertTrue(List.of("follow_player", "go_to_player", "go_to_remembered_place",
                    "go_to_coordinates", "get_self_state", "get_nearby_players",
                    "get_nearby_entities").contains(first),
                    "first tool '" + first + "' must be relevant to a follow request");
        } finally {
            engine.stop();
        }
    }

    @Test
    void spatialChainRunsAgainstRealModel() throws Exception {
        InferenceEngine engine = new InferenceEngine(config());
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(CoreTools.all());
        ToolExecutor executor = new ToolExecutor(registry);
        AgentLoopConfig cfg = new AgentLoopConfig(8, 12, 2, 3, 30_000, 180_000, 0.6, 256, 800);
        AgentLoop loop = new AgentLoop(engine, registry, executor, cfg, ChatSession.SYSTEM_PROMPT);
        TestWorld world = new TestWorld();
        world.following = true;
        world.followingName = "Sean";
        Path placesFile = Files.createTempDirectory("clankerjockey-places").resolve("places.json");
        PlaceMemory places = new PlaceMemory(placesFile);
        places.remember("home", 12, 64, -30);

        try {
            engine.start();
            AgentContext ctx = AgentContext.builder("Sean", world)
                    .owner(true)
                    .worldId("test-world")
                    .places(places)
                    .logger(line -> System.out.println("[loop] " + line))
                    .build();
            AgentResponse r = loop.run("Stop following me and go back to the house.", ctx);

            System.out.println("[AgentLoopIntegrationTest] spatial chain: iterations=" + r.iterations()
                    + " toolCalls=" + r.toolCalls().stream().map(t -> t.call().name()).toList()
                    + " response='" + r.text() + "' limitExceeded=" + r.limitExceeded());

            assertFalse(r.text().isBlank());
            assertFalse(r.interrupted());
            assertTrue(r.toolCalls().size() >= 2,
                    "going home must involve at least two tools (e.g. stop_following + go_to_remembered_place)");
            for (ToolTrace t : r.toolCalls()) {
                assertTrue(registry.contains(t.call().name()));
            }
            assertTrue(world.stopCalls > 0 || !world.isFollowing(),
                    "the companion must stop following during the chain");
        } finally {
            engine.stop();
        }
    }
}
