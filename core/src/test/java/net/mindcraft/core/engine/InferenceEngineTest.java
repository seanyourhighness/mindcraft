package net.mindcraft.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Integration test: spawns the REAL llama-server binary and runs the REAL GGUF
 * model over HTTP. Skipped (with message) when MINDCRAFT_LLAMA_SERVER or
 * MINDCRAFT_TEST_MODEL are unset, so CI machines without models still pass.
 */
@Tag("integration")
class InferenceEngineTest {

    private static final String SERVER_ENV = "MINDCRAFT_LLAMA_SERVER";
    private static final String MODEL_ENV = "MINDCRAFT_TEST_MODEL";

    private static Path server;
    private static Path model;

    @BeforeAll
    static void resolveRealArtifacts() {
        server = envPath(SERVER_ENV);
        Assumptions.assumeTrue(server != null, SERVER_ENV + " not set - skipping integration test");
        model = envPath(MODEL_ENV);
        Assumptions.assumeTrue(model != null, MODEL_ENV + " not set - skipping integration test");
        Assumptions.assumeTrue(Files.isExecutable(server), SERVER_ENV + " is not an executable file: " + server);
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
                .port(0) // auto-pick a free port
                .threads(4)
                .contextSize(2048)
                .build();
    }

    @Test
    void startGenerateStopAgainstRealModel() throws Exception {
        InferenceEngine engine = new InferenceEngine(config());
        try {
            long t0 = System.nanoTime();
            engine.start();
            assertTrue(engine.isRunning(), "engine must be running after start()");
            assertTrue(engine.port() > 0, "a real port must have been resolved");

            GenOptions opts = new GenOptions(30, 0.2, 42L, null);
            String out1 = engine.generate("Say hello in one short sentence.", opts);
            long tGen = System.nanoTime();

            assertTrue(!out1.isBlank(), "generation must return non-blank text, got: '" + out1 + "'");
            System.out.println("[InferenceEngineTest] generated " + out1.length() + " chars: " + out1);
            System.out.println("[InferenceEngineTest] start+generate took "
                    + String.format("%.2f", (tGen - t0) / 1e9) + "s (includes model load)");

            // Fixed seed must make output reproducible (deterministic-ish).
            String out2 = engine.generate("Say hello in one short sentence.", opts);
            assertEquals(out1, out2, "same seed + same prompt must reproduce the same text");
        } finally {
            engine.stop();
        }

        assertFalse(engine.isRunning(), "engine must not be running after stop()");
        assertNotEquals(-1, engine.exitCode(), "process must have actually exited");
        System.out.println("[InferenceEngineTest] llama-server exit code: " + engine.exitCode());
    }

    @Test
    void generateBeforeStartThrows() {
        InferenceEngine engine = new InferenceEngine(config());
        EngineException e = assertThrows(EngineException.class,
                () -> engine.generate("hello", new GenOptions()));
        assertTrue(e.getMessage().toLowerCase().contains("start"),
                "error should mention start(): " + e.getMessage());
    }

    @Test
    void stopIsIdempotent() throws Exception {
        InferenceEngine engine = new InferenceEngine(config());
        engine.start();
        engine.stop();
        engine.stop(); // second stop must be a no-op, not an error
        assertFalse(engine.isRunning());
        engine.close(); // close after stop must also be a no-op
        assertFalse(engine.isRunning());
    }
}
